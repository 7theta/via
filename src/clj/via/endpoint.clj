;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.endpoint
  (:require [via.interceptor :refer [->interceptor]]
            [via.authenticator :refer [authenticate]]
            [org.httpkit.server :refer [with-channel on-close on-receive
                                        sec-websocket-accept] :as http]
            [cognitect.transit :as transit]
            [utilis.fn :refer [fsafe]]
            [utilis.logic :refer [xor]]
            [clojure.set :refer [union difference]]
            [integrant.core :as ig])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(declare encode decode send! channels-by-tag disconnect!)

(def interceptor)

(defmethod ig/init-key :via/endpoint [_ {:keys [authenticator]}]
  (let [subscriptions (atom {})
        clients (atom {})
        params (atom {})
        endpoint {:subscriptions subscriptions
                  :clients clients}]
    (alter-var-root
     #'interceptor
     (constantly
      (->interceptor
       :id :via.endpoint/interceptor
       :before #(let [request (:request %)
                      client-id (:client-id %)]
                  (update % :coeffects merge
                          {:endpoint (fn [] endpoint)
                           :request (dissoc request :ring-request)
                           :ring-request (:ring-request request)
                           :client-id client-id}))
       :after (fn [context]

                (if-let [{:keys [client-id tag]} (get-in context [:effects :disconnect])]
                  (disconnect!
                   (fn [] endpoint)
                   :client-id client-id
                   :tag tag)
                  (do

                    (when-let [response (get-in context [:effects :reply])]
                      (when (:request-id context)
                        (send! (fn [] endpoint) response
                               :type :reply
                               :client-id (:client-id context)
                               :params {:status (:status context)
                                        :request-id (:request-id context)})))

                    (let [add-tags (get-in context [:effects :add-tags])
                          remove-tags (get-in context [:effects :remove-tags])
                          tags (get-in context [:effects :tags])]
                      (when (or (seq add-tags) (seq remove-tags) (seq tags))
                        (when-let [client-id (:client-id context)]
                          (swap! clients update-in [client-id :tags]
                                 #(if (seq tags)
                                    (set tags)
                                    (cond-> (set %)
                                      add-tags (union (set add-tags))
                                      remove-tags (difference (set remove-tags))))))))))

                context))))
    (fn
      ([] endpoint)
      ([request]
       (let [handle-event (fn [message data]
                            (doseq [handler (->> @subscriptions vals (map message) (remove nil?))]
                              (handler data)))]
         (with-channel request channel
           (let [client-id (str (java.util.UUID/randomUUID))]
             (handle-event :open {:client-id client-id :status :initial})
             (swap! clients assoc client-id {:channel channel :ring-request request})
             (on-close channel
                       (fn [status]
                         (swap! clients dissoc client-id)
                         (handle-event :auth-close {:client-id client-id :status status})
                         (handle-event :close {:client-id client-id :status status})))
             (on-receive channel
                         (fn [message]
                           (let [message (decode message)]
                             (case (:type message)
                               :message (handle-event
                                         :message
                                         (merge message
                                                {:client-id client-id
                                                 :ring-request request})))))))))))))

(defn subscribe
  [endpoint callbacks]
  (let [key (str (java.util.UUID/randomUUID))]
    (swap! (:subscriptions (endpoint)) assoc key callbacks)
    key))

(defn unsubscribe
  [endpoint key]
  (when-let [subs (:subscriptions ((fsafe endpoint)))]
    (swap! subs dissoc key)))

(defn send!
  "Asynchronously sends `event` to the client for `client-id`"
  [endpoint message & {:keys [type client-id tag params]
                       :or {type :message}}]
  {:pre [(xor client-id tag)]}
  (let [encoded-message (encode (merge {:type type :payload message} params))
        send-to-channel! #(http/send! % encoded-message false)]
    (if tag
      (doseq [channel (channels-by-tag endpoint tag)]
        (send-to-channel! channel))
      (if-let [channel (get-in @(:clients (endpoint)) [client-id :channel])]
        (send-to-channel! channel)
        (throw (ex-info "Client not connected" {:client-id client-id}))))))

(defn broadcast!
  "Asynchronously sends `message` to all connected clients"
  [endpoint message]
  (doseq [c (keys @(:clients (endpoint)))]
    (send! endpoint message :client-id c)))

(defn disconnect!
  [endpoint & {:keys [client-id tag]}]
  {:pre [(xor client-id tag)]}
  (doseq [channel (if tag
                    (channels-by-tag endpoint)
                    [(get-in @(:clients (endpoint))
                             [client-id :channel])])]
    (http/close channel)))

(defn- encode
  [data]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json) data)
    (.toString out)))

(defn- decode
  [data]
  (let [in (ByteArrayInputStream. (.getBytes data))]
    (transit/read (transit/reader in :json))))

(defn- channels-by-tag
  [endpoint tag]
  (->> @(:clients (endpoint)) vals
       (filter #(get (:tags %) tag))
       (map :channel)
       (remove nil?)))
