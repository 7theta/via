;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.endpoint
  (:require [via.defaults :refer [default-via-endpoint protocol-version]]
            [signum.interceptors :refer [->interceptor]]
            [tempus.transit :as tt]
            [haslett.client :as ws]
            [haslett.format :as fmt]
            [utilis.fn :refer [fsafe]]
            [utilis.map :refer [compact]]
            [utilis.types.string :refer [->string]]
            [utilis.js :as j]
            [cljs.core.async :as a :refer [chan close! <! >! poll! timeout go alt! put!]]
            [re-frame.core :refer [reg-sub-raw] :as re-frame]
            [reagent.ratom :refer [reaction]]
            [reagent.core :as r]
            [integrant.core :as ig]
            [cognitect.transit :as transit]
            [goog.string :refer [format]]
            [goog.string.format]
            [clojure.string :as st]))

(declare connect! disconnect! connected? send! default-via-url exponential-seq send*)

(def interceptor)

(defmethod ig/init-key :via/endpoint
  [_ {:keys [url
             auto-connect
             auto-reconnect
             max-reconnect-interval
             connect-opts
             transit-handlers]
      :or {auto-connect true
           auto-reconnect true
           max-reconnect-interval 5000
           url (default-via-url)}
      :as opts}]
  (let [endpoint {:url url
                  :download-url (-> url
                                    (st/replace #"^wss" "https")
                                    (st/replace #"^ws" "http"))
                  :outbound-ch (atom nil)
                  :control-ch (atom nil)
                  :connect-state (r/atom nil)
                  :subscriptions (atom {})
                  :requests (atom {})
                  :format (reify fmt/Format
                            (read  [_ s]
                              (transit/read
                               (transit/reader :json {:handlers (merge (:read tt/handlers)
                                                                       (get transit-handlers :read {}))}) s))
                            (write [_ v]
                              (transit/write
                               (transit/writer :json {:handlers (merge (:write tt/handlers)
                                                                       (get transit-handlers :write {}))}) v)))}
        connect-opts (compact
                      (assoc connect-opts
                             :auto-reconnect auto-reconnect
                             :max-reconnect-interval max-reconnect-interval))]
    (reg-sub-raw
     :via.endpoint/connected
     (fn []
       (reaction (connected? (fn [] endpoint)))))
    (set!
     interceptor
     (->interceptor
      :id :via.endpoint/interceptor
      :before #(update % :coeffects merge {:endpoint endpoint :request (:request %)})
      :after (fn [context]
               (when-let [reply (get-in context [:effects :via/reply])]
                 (send! (fn [] endpoint) reply
                        :type :reply
                        :client-id (:client-id context)
                        :params {:status (get-in context [:effects :via/status])
                                 :request-id (:request-id context)}))
               context)))
    (when auto-connect (connect! (fn [] endpoint) connect-opts))
    (fn [] endpoint)))

(defmethod ig/halt-key! :via/endpoint
  [_ endpoint]
  (disconnect! endpoint))

(declare handle-event handle-reply handle-connection-context handle-download
         append-query-params establish-connection-context)

(defn connect!
  ([endpoint] (connect! endpoint nil))
  ([endpoint {:keys [params
                     auto-reconnect
                     max-reconnect-interval
                     protocols
                     binary-type]}]
   (let [control-ch (reset! (:control-ch (endpoint)) (chan))
         connection-context (atom {::version protocol-version})
         handle-message (fn handle-message [message]
                          (case (:type message)
                            :connection-context (handle-connection-context connection-context message)
                            :message (handle-event (endpoint) :message message)
                            :reply (handle-reply (endpoint) message)
                            :download (handle-download (endpoint) handle-message message)
                            (js/console.warn "via: unhandled message from server: "
                                             (if message (clj->js message) "nil"))))
         handle-close #(do (reset! (:connect-state (endpoint)) :disconnected)
                           (reset! (:outbound-ch (endpoint)) nil)
                           (handle-event (endpoint) :close (merge {:status :forced} %)))]
     (go (try
           (loop [backoff-sq (when auto-reconnect (exponential-seq 2 max-reconnect-interval))]
             (let [return (ws/connect (append-query-params (:url (endpoint)) params)
                                      {:format (:format (endpoint))})
                   {:keys [socket source sink close-status] :as stream} (<! return)
                   result (if (ws/connected? stream)
                            (do (reset! (:outbound-ch (endpoint)) sink)
                                (establish-connection-context endpoint stream connection-context)
                                (loop []
                                  (alt!
                                    control-ch (do (ws/close stream) :exit)
                                    source ([message] (handle-message message) (recur))
                                    close-status ([status] (do (handle-close status) :disconnected)))))
                            :connect-failed)]
               (when (and auto-reconnect (#{:connect-failed :disconnected} result))
                 (let [backoff-sq (if (= :connect-failed result)
                                    backoff-sq
                                    (exponential-seq 2 max-reconnect-interval))
                       interval (first backoff-sq)]
                   (js/console.info (str "Disconnected from via endpoint. Reconnecting in " (str interval "ms.")))
                   (<! (timeout interval))
                   (recur (rest backoff-sq))))))
           (close! control-ch)
           (catch js/Error e
             (js/console.error e "Error occurred in via connection/message loop.")))
         (js/console.info "via connection loop exited.")))))

(defn disconnect!
  [endpoint]
  (when (connected? endpoint)
    (let [endpoint (endpoint)]
      (when (and @(:control-ch endpoint)
                 @(:outbound-ch endpoint))
        (handle-event endpoint :close {:status :normal})
        (close! @(:control-ch endpoint))
        (reset! (:control-ch endpoint) nil)
        (reset! (:outbound-ch endpoint) nil)))))

(defn connected?
  [endpoint]
  (= :connected @(:connect-state (endpoint))))

(defn subscribe
  [endpoint callbacks]
  (let [key (str (random-uuid))]
    (swap! (:subscriptions (endpoint)) assoc key callbacks)
    (when (connected? endpoint)
      (when-let [handler (:open callbacks)]
        (handler {:status :progress})))
    key))

(defn dispose
  [endpoint key]
  (swap! (:subscriptions (endpoint)) dissoc key))

(defn send!
  [endpoint message & {:keys [type success-fn failure-fn timeout timeout-fn]
                       :or {type :message}
                       :as options}]
  (if-not (connected? endpoint)
    ((fsafe failure-fn) {:status :disconnected})
    (send* endpoint message (assoc options :type type))))


;;; Private

(defn- handle-event
  [endpoint type data]
  (doseq [handler (->> @(:subscriptions endpoint) vals (map type) (remove nil?))]
    (try (handler data)
         (catch js/Error e
           (js/console.error e)))))

(defn- handle-reply
  [endpoint reply]
  (if-let [request (get @(:requests endpoint) (:request-id reply))]
    (do (js/clearTimeout (:timer request))
        ((fsafe ((if (= 200 (:status reply)) :success-fn :failure-fn) request))
         (select-keys reply [:status :payload])))
    (js/console.warn ":via/endpoint reply with invalid request-id" (pr-str reply))))

(defn- handle-connection-context
  [connection-context {:keys [payload]}]
  (let [[event-id context] payload]
    (condp = event-id
      :via.connection-context/updated (reset! connection-context context)
      (js/console.warn "Unknown via connection-context message" (pr-str payload)))))

(defn- decode-downloaded-message
  [format message]
  (js/Promise.
   (fn [resolve reject]
     (resolve (fmt/read format message)))))

(defn- handle-download
  [{:keys [url download-url format]} handle-message {:keys [payload] :as message}]
  (-> (str download-url "?op=download")
      (js/fetch (clj->js {:headers {"Authorization" (str "Bearer " payload)}}))
      (j/call :then #(if (= 200 (j/get % :status))
                       (j/call % :text)
                       (throw (js/Error. (str "Failed to download\n" (pr-str message))))))
      (j/call :then (partial decode-downloaded-message format))
      (j/call :then handle-message)
      (j/call :catch #(js/console.warn %))))

(defn- default-via-url
  []
  (when-let [location (.-location js/window)]
    (str (if (= "http:" (.-protocol location)) "ws://" "wss://")
         (.-host location) default-via-endpoint)))

(defn- append-query-params
  [url query-params]
  (let [query-params (->> query-params
                          (map (fn [[k v]] (format "%s=%s" (->string k) (->string v))))
                          (st/join "&") not-empty)]
    (js/encodeURI (cond-> url query-params (str "?" query-params)))))

(defn- exponential-seq
  ([base max-value]
   (map #(min % max-value) (exponential-seq base)))
  ([base]
   (let [base (js/Math.abs base)]
     (->> {:step 1 :value base}
          (iterate
           (fn [{:keys [step]}]
             {:value (js/Math.pow base (inc step))
              :step (inc step)}))
          (map :value)))))

(defn- send*
  [endpoint message {:keys [type success-fn failure-fn timeout timeout-fn]
                     :or {type :message}
                     :as options}]
  (if message
    (let [endpoint (endpoint)
          do-send (fn [message params]
                    (if-let [outbound-ch @(:outbound-ch endpoint)]
                      (go (>! outbound-ch
                              (merge {:type type
                                      :payload message} params)))
                      (throw (js/Error. ":via/endpoint not connected"))))]
      (if-not (or success-fn failure-fn)
        (do-send message nil)
        (let [request-id (str (random-uuid))]
          (swap! (:requests endpoint)
                 assoc request-id {:success-fn success-fn
                                   :failure-fn failure-fn
                                   :message message
                                   :timer (js/setTimeout (fsafe timeout-fn) timeout)})
          (do-send message {:request-id request-id
                            :timeout timeout}))))
    (js/console.warn ":via/endpoint attempting to send nil message")))

(defn- establish-connection-context
  [endpoint {:keys [sink close-status] :as stream} connection-context]
  (let [ready-ch (chan)
        response-handler (fn [status]
                           (fn [response]
                             (go (>! ready-ch [status response])
                                 (close! ready-ch))))]
    (send* endpoint [:via.connection-context/replace @connection-context]
           {:success-fn (response-handler :success)
            :failure-fn (response-handler :failure)
            :timeout 10000
            :timeout-fn (response-handler :timeout)})
    (go (let [[status response] (<! ready-ch)]
          (condp = status
            :success (let [control-ch @(:control-ch (endpoint))]
                       (reset! (:connect-state (endpoint)) :connected)
                       (handle-event (endpoint) :open {:status :initial}))
            :failure (js/console.warn "Unable to establish connection context" (pr-str response))
            :timeout (js/console.warn "Timed out establishing connection context" (pr-str response)))))))
