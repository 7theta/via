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
  (:require [signum.interceptors :refer [->interceptor]]
            [org.httpkit.server :refer [with-channel on-close on-receive
                                        sec-websocket-accept websocket?] :as http]
            [cognitect.transit :as transit]
            [utilis.fn :refer [fsafe]]
            [utilis.logic :refer [xor]]
            [clojure.set :refer [union difference]]
            [integrant.core :as ig])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(declare encode decode send! channels-by-tag disconnect!
         data replace-data! merge-data!)

(def interceptor)

(defmethod ig/init-key :via/endpoint [_ {:keys []}]
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
       :before #(update % :coeffects merge {:endpoint (fn [] endpoint)
                                            :client (get @clients (get-in % [:request :client-id]))
                                            :request (:request %)})
       :after (fn [context]
                (if-let [{:keys [client-id tag]} (get-in context [:effects :via/disconnect])]
                  (disconnect! (fn [] endpoint) :client-id client-id :tag tag)
                  (when-let [client-id (get-in context [:request :client-id])]
                    (when-let [replace-data (get-in context [:effects :via/replace-data])]
                      (replace-data! (fn [] endpoint) client-id replace-data))
                    (when-let [merge-data (get-in context [:effects :via/merge-data])]
                      (merge-data! (fn [] endpoint) client-id merge-data))

                    (let [add-tags (get-in context [:effects :via/add-tags])
                          remove-tags (get-in context [:effects :via/remove-tags])
                          replace-tags (get-in context [:effects :via/replace-tags])]
                      (when (or (seq add-tags) (seq remove-tags) (seq replace-tags))
                        (swap! clients update-in [client-id :tags]
                               #(cond-> (set %)
                                  add-tags (union (set add-tags))
                                  remove-tags (difference (set remove-tags))
                                  replace-tags ((constantly (set replace-tags)))))))

                    (when-let [reply (get-in context [:effects :via/reply])]
                      (send! (fn [] endpoint) reply
                             :type :reply
                             :client-id (get-in context [:request :client-id])
                             :params {:status (get-in context [:effects :via/status])
                                      :request-id (get-in context [:request :request-id])}))))
                context))))
    (fn
      ([] endpoint)
      ([request]
       (let [handle-event (fn [event-type event]
                            (doseq [handler (->> @subscriptions vals (map event-type) (remove nil?))]
                              (handler event)))]
         (with-channel request channel
           (if (websocket? channel)
             (let [client-id (get-in request [:headers "sec-websocket-key"])]
               (swap! clients assoc client-id {:channel channel :ring-request request :data {}})
               (handle-event :open {:client-id client-id :status :initial})
               (on-close channel
                         (fn [status]
                           (swap! clients dissoc client-id)
                           (handle-event :close {:client-id client-id :status status})))
               (on-receive channel
                           (fn [message]
                             (handle-event :message (let [{:keys [payload request-id]} (decode message)]
                                                      {:client-id client-id
                                                       :request-id request-id
                                                       :ring-request request
                                                       :payload payload})))))
             (http/send! channel {:status 404} true))))))))

(defmethod ig/halt-key! :via/endpoint
  [_ endpoint]
  (doseq [c (->> @(:clients (endpoint)) (map :channel) (remove nil?))]
    (http/close c)))

(defn subscribe
  [endpoint callbacks]
  (let [key (str (java.util.UUID/randomUUID))]
    (swap! (:subscriptions (endpoint)) assoc key callbacks)
    key))

(defn dispose
  [endpoint key]
  (swap! (:subscriptions (endpoint)) dissoc key))

(defn send!
  "Asynchronously sends `message` to the client for `client-id`"
  [endpoint message & {:keys [type client-id tag params]
                       :or {type :message}}]
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
  (doseq [client-id (keys @(:clients (endpoint)))]
    (send! endpoint message :client-id client-id)))

(defn disconnect!
  [endpoint & {:keys [client-id tag]}]
  (doseq [channel (if tag
                    (channels-by-tag endpoint tag)
                    [(get-in @(:clients (endpoint)) [client-id :channel])])]
    (http/close channel)))

;;; Private

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

(defn- data
  [endpoint client-id]
  (get-in @(:clients (endpoint)) [client-id :data]))

(defn- merge-data!
  [endpoint client-id data]
  (when client-id
    (swap! (:clients (endpoint)) update-in [client-id :data] merge data)))

(defn- replace-data!
  [endpoint client-id data]
  (when client-id
    (swap! (:clients (endpoint)) assoc-in [client-id :data] data)))
