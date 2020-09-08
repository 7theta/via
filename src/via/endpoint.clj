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
            [org.httpkit.server :refer [as-channel on-close on-receive
                                        sec-websocket-accept websocket?] :as http]
            [cognitect.transit :as transit]
            [utilis.fn :refer [fsafe]]
            [utilis.logic :refer [xor]]
            [clojure.set :refer [union difference]]
            [integrant.core :as ig])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(declare encode decode send! channels-by-tag disconnect!
         handle-effect handle-connection-context-changed
         run-effects handle-event)

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
       :after #(do (run-effects endpoint %) %))))
    (fn
      ([] endpoint)
      ([request]
       (let [client-id (get-in request [:headers "sec-websocket-key"])]
         (if-not (and (:websocket? request) (not-empty client-id))
           {:status 404}
           (as-channel request
                       {:on-open
                        (fn [channel]
                          (swap! clients assoc client-id {:channel channel
                                                          :ring-request request
                                                          :connection-context nil})
                          (handle-event endpoint :open {:client-id client-id
                                                        :status :initial}))
                        :on-close
                        (fn [channel status]
                          (swap! clients dissoc client-id)
                          (handle-event endpoint :close {:client-id client-id :status status}))
                        :on-receive
                        (fn [channel message]
                          (handle-event endpoint :message (let [{:keys [payload request-id]} (decode message)]
                                                            {:client-id client-id
                                                             :request-id request-id
                                                             :ring-request request
                                                             :payload payload})))})))))))

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
        (println "warn: no client found to send message to"
                 {:client-id client-id
                  :message message})))))

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

(defn connection-context
  [endpoint client-id]
  (get-in @(:clients (endpoint)) [client-id :connection-context]))

(defmacro validate
  [schema value message]
  (require '[environ.core :refer [env]])
  (let [env @(resolve 'env)]
    (when (= "true" (env :malli))
      (require '[malli.core :as m])
      `(assert (m/validate ~schema ~value) ~message))))

(defn merge-connection-context!
  [endpoint client-id connection-context]
  (validate [string?] client-id (str "Must provide valid client-id " {:client-id client-id}))
  (-> (:clients (endpoint))
      (swap! update-in [client-id :connection-context] merge connection-context)
      (get-in [client-id :connection-context])))

(defn replace-connection-context!
  [endpoint client-id connection-context]
  (validate [string?] client-id (str "Must provide valid client-id " {:client-id client-id}))
  (-> (:clients (endpoint))
      (swap! assoc-in [client-id :connection-context] connection-context)
      (get-in [client-id :connection-context])))

;;; Effects

(defmulti handle-effect (fn [[effect-id _]] effect-id))

(defmethod handle-effect :via/disconnect
  [[_ {:keys [endpoint context]}]]
  (let [{:keys [client-id tag]} (get-in context [:effects :via/disconnect])]
    (disconnect! (fn [] endpoint) :client-id client-id :tag tag)))

(defmethod handle-effect :via/add-tags
  [[_ {:keys [context endpoint]}]]
  (let [client-id (get-in context [:request :client-id])
        add-tags (get-in context [:effects :via/add-tags])
        {:keys [clients]} endpoint]
    (validate [string?] client-id (str "Must provide valid client-id " {:client-id client-id}))
    (swap! clients update-in [client-id :tags] #(union (set %) (set add-tags)))))

(defmethod handle-effect :via/remove-tags
  [[_ {:keys [context endpoint]}]]
  (let [client-id (get-in context [:request :client-id])
        remove-tags (get-in context [:effects :via/remove-tags])
        {:keys [clients]} endpoint]
    (validate [string?] client-id (str "Must provide valid client-id " {:client-id client-id}))
    (swap! clients update-in [client-id :tags] #(difference (set %) (set remove-tags)))))

(defmethod handle-effect :via/replace-tags
  [[_ {:keys [context endpoint]}]]
  (let [client-id (get-in context [:request :client-id])
        replace-tags (get-in context [:effects :via/replace-tags])
        {:keys [clients]} endpoint]
    (validate [string?] client-id (str "Must provide valid client-id " {:client-id client-id}))
    (swap! clients assoc-in [client-id :tags] (set replace-tags))))

(defmethod handle-effect :via/reply
  [[_ {:keys [endpoint context]}]]
  (let [reply (get-in context [:effects :via/reply])]
    (when-let [request-id (get-in context [:request :request-id])]
      (send! (fn [] endpoint) reply
             :type :reply
             :client-id (get-in context [:request :client-id])
             :params {:status (get-in context [:effects :via/status])
                      :request-id request-id}))))

(defmethod handle-effect :via/replace-connection-context
  [[_ {:keys [endpoint context] :as params}]]
  (let [client-id (get-in context [:request :client-id])
        replace-connection-context (get-in context [:effects :via/replace-connection-context])
        connection-context (replace-connection-context! (fn [] endpoint) client-id replace-connection-context)]
    (handle-connection-context-changed params connection-context)))

(defmethod handle-effect :via/merge-connection-context
  [[_ {:keys [endpoint context] :as params}]]
  (let [client-id (get-in context [:request :client-id])
        merge-connection-context (get-in context [:effects :via/merge-connection-context])
        connection-context (merge-connection-context! (fn [] endpoint) client-id merge-connection-context)]
    (handle-connection-context-changed params connection-context)))

(defmethod handle-effect :via/status
  [[_ _]]
  )

(defmethod handle-effect :default
  [[effect-id {:keys [context]}]]
  (throw (ex-info "Unknown effect"
                  {:effect effect-id
                   :params (get-in context [:effects effect-id])})))

;;; Private

(defn- encode
  [^String data]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json) data)
    (.toString out)))

(defn- decode
  [^String data]
  (let [in (ByteArrayInputStream. (.getBytes data))]
    (transit/read (transit/reader in :json))))

(defn- channels-by-tag
  [endpoint tag]
  (->> @(:clients (endpoint)) vals
       (filter #(get (:tags %) tag))
       (map :channel)
       (remove nil?)))

(defn- handle-event
  ([endpoint event-type event]
   (handle-event nil endpoint event-type event))
  ([context endpoint event-type event]
   (doseq [handler (->> @(:subscriptions endpoint)
                        vals
                        (map event-type)
                        (remove nil?))]
     (let [result (handler event)]
       (when (and context (map? result))
         (run-effects endpoint (assoc context :effects result)))))))

(defn- handle-connection-context-changed
  [{:keys [endpoint context] :as params} connection-context]
  (let [{:keys [client-id]} (:request context)]
    (handle-event context endpoint :connection-context-changed connection-context)
    (send! (fn [] endpoint)
           [:via.connection-context/updated connection-context]
           :client-id client-id
           :type :connection-context)))

(def ^:private known-effects
  [:via/replace-connection-context
   :via/merge-connection-context
   :via/add-tags
   :via/remove-tags
   :via/replace-tags
   :via/reply
   :via/status
   :via/disconnect])

(def ^:private known-effects-set (set known-effects))

(defn- run-effects
  [endpoint context]
  (let [effect-params {:endpoint endpoint
                       :context context}]
    (doseq [effect-id known-effects]
      (when (contains? (:effects context) effect-id)
        (handle-effect [effect-id effect-params])))
    (doseq [effect-id (keys (:effects context))]
      (when (and (= :via (keyword (namespace effect-id)))
                 (not (known-effects-set effect-id)))
        (throw (ex-info "Unrecognized via effect" {:effect-id effect-id}))))))
