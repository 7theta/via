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
            [integrant.core :as ig])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(declare encode decode send!)

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
       :before #(update % :coeffects merge {:endpoint (fn [] endpoint) :request (:request %)})
       :after (fn [context]
                (when-let [response (get-in context [:effects :reply])]
                  (when (:request-id context)
                    (send! (fn [] endpoint) response
                           :type :reply
                           :client-id (:client-id context)
                           :params {:status (:status context)
                                    :request-id (:request-id context)})))
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
             (swap! clients assoc client-id {:channel channel})
             (on-close channel
                       (fn [status]
                         (swap! clients dissoc client-id)
                         (handle-event :auth-close {:client-id client-id :status status})
                         (handle-event :close {:client-id client-id :status status})))
             (on-receive channel
                         (fn [message]
                           (let [message (decode message)]
                             (case (:type message)
                               :message
                               (handle-event :message (merge message {:client-id client-id}))
                               :authentication-request
                               (if-not authenticator
                                 (do
                                   (http/send! channel (encode
                                                        {:type :reply
                                                         :request-id (:request-id message)
                                                         :status 500}) false)
                                   (throw (ex-info "Authenticator not configured")))
                                 (http/send!
                                  channel
                                  (encode
                                   (let [{:keys [id password]} (:payload message)]
                                     (if-let [user (authenticate authenticator id password)]
                                       (do
                                         (swap! clients update client-id assoc :user user)
                                         (handle-event :auth-open {:client-id client-id :user user})
                                         {:type :reply
                                          :request-id (:request-id message)
                                          :status 200
                                          :payload user})
                                       (do (handle-event :auth-close {:client-id client-id})
                                           {:type :reply
                                            :request-id (:request-id message)
                                            :status 403
                                            :payload {:error :invalid-credential}}))))
                                  false)))))))))))))

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
  [endpoint message & {:keys [type client-id id params]
                       :or {type :message}}]
  {:pre [(or client-id id)]}
  (if-let [channel (get-in @(:clients (endpoint)) [client-id :channel])]
    (http/send! channel (encode (merge {:type type :payload message} params)) false)
    (throw (ex-info "Client not connected" {:client-id client-id}))))

(defn broadcast!
  "Asynchronously sends `message` to all connected clients"
  [endpoint message]
  (doseq [c (keys @(:clients (endpoint)))]
    (send! endpoint message :client-id c)))

(defn- encode
  [data]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json) data)
    (.toString out)))

(defn- decode
  [data]
  (let [in (ByteArrayInputStream. (.getBytes data))]
    (transit/read (transit/reader in :json))))
