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
  (:require [via.defaults :as via-defaults]
            [signum.interceptors :refer [->interceptor]]
            [org.httpkit.server :refer [as-channel on-close on-receive
                                        sec-websocket-accept websocket?] :as http]
            [cognitect.transit :as transit]
            [buddy.hashers :as bh]
            [buddy.sign.jwt :as jwt]
            [buddy.core.nonce :as bn]
            [tick.core :as t]
            [utilis.fn :refer [fsafe]]
            [utilis.logic :refer [xor]]
            [clojure.set :refer [union difference]]
            [integrant.core :as ig]
            [clojure.string :as st])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

;;; Declarations

(declare send! channels-by-tag disconnect! encode decode
         handle-effect handle-download handle-connection-context-changed
         run-effects handle-event channels send-to-channel! audit-downloads)

(def interceptor)

;;; Integrant

(defmethod ig/init-key :via/endpoint
  [_ {:keys [max-ws
             download-secret
             download-expiry-seconds
             downloads-audit-interval-seconds]
      :or {max-ws (* 100 1024)
           download-secret (bn/random-bytes 32)
           download-expiry-seconds 30
           downloads-audit-interval-seconds 3600}}]
  (let [subscriptions (atom {})
        clients (atom {})
        params (atom {})
        downloads (atom {})
        downloads-audit-future (audit-downloads downloads downloads-audit-interval-seconds)
        endpoint {:max-ws max-ws
                  :download-secret download-secret
                  :download-expiry-seconds download-expiry-seconds
                  :downloads downloads
                  :downloads-audit-future downloads-audit-future
                  :subscriptions subscriptions
                  :clients clients}]
    (alter-var-root
     #'interceptor
     (constantly
      (->interceptor
       :id :via.endpoint/interceptor
       :before #(update % :coeffects
                        merge {:endpoint (fn [] endpoint)
                               :client (get @clients (get-in % [:request :client-id]))
                               :request (:request %)})
       :after #(do (run-effects endpoint %) %))))
    (fn
      ([] endpoint)
      ([request]
       (let [client-id (get-in request [:headers "sec-websocket-key"])]
         (cond
           (and (:websocket? request) (not-empty client-id))
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
                          (handle-event endpoint :message
                                        (let [{:keys [payload request-id]} (decode message)]
                                          {:client-id client-id
                                           :request-id request-id
                                           :ring-request request
                                           :payload payload})))})

           (and (not (:websocket? request))
                (= "download" (get-in request [:query-params "op"])))
           (handle-download downloads download-secret request)

           :else {:status 404}))))))

(defmethod ig/halt-key! :via/endpoint
  [_ endpoint]
  (doseq [c (->> @(:clients (endpoint))
                 (map :channel)
                 (remove nil?))]
    (http/close c))
  ((fsafe future-cancel) (:downloads-audit-future (endpoint))))

(defn subscribe
  [endpoint callbacks]
  (let [key (str (java.util.UUID/randomUUID))]
    (swap! (:subscriptions (endpoint)) assoc key callbacks)
    key))

(defn dispose
  [endpoint key]
  (swap! (:subscriptions (endpoint)) dissoc key))

;;; API

(defn send!
  "Asynchronously sends `message` to the client for `client-id`"
  [endpoint message & {:keys [type client-id tag params]
                       :or {type :message}
                       :as args}]
  (if-let [channels (not-empty (channels endpoint args))]
    (let [{:keys [max-ws
                  download-secret
                  download-expiry-seconds
                  downloads]} (endpoint)
          message (merge {:type type :payload message} params)
          encoded-message (encode message)
          large-message? (> (count encoded-message) max-ws)]
      (doseq [{:keys [channel version]} channels]
        (if large-message?
          (if (= version "2")
            (let [expiry (->> :seconds
                              (t/new-duration download-expiry-seconds)
                              (t/+ (t/now)))
                  payload (jwt/encrypt {:exp expiry
                                        ;; ensure this token is unique
                                        :req (str (java.util.UUID/randomUUID))}
                                       download-secret)]
              (swap! downloads assoc payload
                     {:expiry expiry
                      :message message})
              (send-to-channel! channel (encode {:type :download :payload payload})))
            (println "warn: unable to send large message to protocol version 1."
                     {:max-ws max-ws
                      :encoded-message-size (count encoded-message)}))
          (send-to-channel! channel encoded-message))))
    (when client-id
      (try (throw (ex-info "warn: no client found to send message to"
                           {:client-id client-id
                            :message message}))
           (catch Exception e
             (println e))))))

(defn broadcast!
  "Asynchronously sends `message` to all connected clients"
  [endpoint message]
  (doseq [client-id (keys @(:clients (endpoint)))]
    (send! endpoint message :client-id client-id)))

(defn disconnect!
  [endpoint & {:keys [client-id tag]}]
  (doseq [channel (if tag
                    (map :channel (channels-by-tag endpoint tag))
                    [(get-in @(:clients (endpoint)) [client-id :channel])])]
    (http/close channel)))

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

(defn- channel-map
  [{:keys [channel connection-context]}]
  (when channel
    {:channel channel
     :version (::version connection-context)}))

(defn- channels-by-tag
  [endpoint tag]
  (->> @(:clients (endpoint)) vals
       (filter #(get (:tags %) tag))
       (map channel-map)
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

(defn- handle-download
  [downloads download-secret request]
  (when-let [authorization (get-in request [:headers "authorization"])]
    (let [[_ token] (st/split authorization #" ")]
      (when (try
              (when token (jwt/decrypt token download-secret))
              (catch Exception _ nil))
        (when-let [message (locking downloads
                             (when-let [{:keys [message]} (get @downloads token)]
                               (swap! downloads dissoc token)
                               message))]
          {:status 200
           :body (ByteArrayInputStream. (.getBytes (encode message)))
           :headers {"Content-Type" "application/octet-stream"}})))))

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

(defn- send-to-channel!
  [channel encoded-message]
  (http/send! channel encoded-message false))

(defn- channels
  [endpoint {:keys [client-id tag]}]
  (if tag
    (channels-by-tag endpoint tag)
    [(channel-map (get @(:clients (endpoint)) client-id))]))

(defn- audit-downloads
  [downloads downloads-audit-interval-seconds]
  (future
    (try (loop []
           (doseq [[key {:keys [expiry]}] @downloads]
             (when (t/> (t/now) expiry)
               (swap! downloads dissoc key)))
           (Thread/sleep (* downloads-audit-interval-seconds 1000))
           (recur))
         (catch Exception e
           (when (not (instance? java.lang.InterruptedException e))
             (println e "Error occurred in downloads audit future."))))))
