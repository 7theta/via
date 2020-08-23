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
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require [via.defaults :refer [default-via-endpoint]]
            [signum.interceptors :refer [->interceptor]]
            [haslett.client :as ws]
            [haslett.format :as fmt]
            [utilis.fn :refer [fsafe]]
            [utilis.map :refer [compact]]
            [utilis.types.string :refer [->string]]
            [cljs.core.async :as a :refer [chan close! <! >! poll! timeout]]
            [integrant.core :as ig]
            [goog.string :refer [format]]
            [goog.string.format]
            [clojure.string :as st]))

(declare connect! disconnect! send! default-via-url exponential-seq)

(def interceptor)

(defmethod ig/init-key :via/endpoint
  [_ {:keys [url
             auto-connect
             auto-reconnect
             max-reconnect-interval
             connect-opts]
      :or {auto-connect true
           auto-reconnect true
           max-reconnect-interval 5000
           url (default-via-url)}
      :as opts}]
  (let [endpoint {:url url
                  :stream (atom nil)
                  :subscriptions (atom {})
                  :control-ch (atom nil)
                  :requests (atom {})}
        connect-opts (compact
                      (assoc connect-opts
                             :auto-reconnect auto-reconnect
                             :max-reconnect-interval max-reconnect-interval))]
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

(declare handle-event handle-reply append-query-params)

(defn connect!
  ([endpoint] (connect! endpoint nil))
  ([endpoint {:keys [params
                     auto-reconnect
                     max-reconnect-interval]}]
   (let [endpoint (endpoint)]
     (when-not @(:stream endpoint)
       (when-let [old-control-ch @(:control-ch endpoint)] (close! old-control-ch))
       (go (try (let [connection-status (atom :disconnected)]
                  (loop [backoff-sq (when auto-reconnect (exponential-seq 2 max-reconnect-interval))]
                    (let [stream (ws/connect (append-query-params (:url endpoint) params)
                                             {:format fmt/transit})
                          control-ch (chan)]
                      (reset! (:control-ch endpoint) control-ch)
                      (when (= :disconnected
                               (<! (go
                                     (let [{:keys [close-status source sink] :as stream} (<! stream)]
                                       (reset! (:stream endpoint) stream)
                                       (when (ws/connected? stream)
                                         (reset! connection-status :connected)
                                         (handle-event endpoint :open {:status :initial}))
                                       (loop []
                                         (alt!
                                           control-ch (do (ws/close stream) :shutdown)
                                           source ([message]
                                                   (if-not (nil? message)
                                                     (case (:type message)
                                                       :message (handle-event endpoint :message message)
                                                       :reply (handle-reply endpoint message))
                                                     (js/console.warn "via: nil message from server"))
                                                   (recur))
                                           close-status ([status]
                                                         (when (not= :disconnected @connection-status)
                                                           (reset! connection-status :disconnected)
                                                           (reset! (:stream endpoint) nil)
                                                           (handle-event endpoint :close (merge {:status :forced} status)))
                                                         :disconnected)))))))
                        (when auto-reconnect
                          (let [reconnect-interval (first backoff-sq)]
                            (<! (timeout reconnect-interval))
                            (recur (rest backoff-sq))))))))
                (catch js/Error e
                  (js/console.error "Error occurred in via connection loop" e))))))))

(defn disconnect!
  [endpoint]
  (let [endpoint (endpoint)]
    (when @(:stream endpoint)
      (handle-event endpoint :close {:status :normal})
      (close! @(:control-ch endpoint))
      (reset! (:control-ch endpoint) nil)
      (reset! (:stream endpoint) nil))))

(defn connected?
  [endpoint]
  (boolean @(:stream (endpoint))))

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
                       :or {type :message}}]
  (if-not (connected? endpoint)
    ((fsafe failure-fn) {:status :disconnected})
    (if message
      (let [endpoint (endpoint)
            do-send (fn [message params]
                      (if-let [stream @(:stream endpoint)]
                        (go (>! (:sink stream)
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
      (js/console.warn ":via/endpoint attempting to send nil message"))))

(defn- handle-event
  [endpoint type data]
  (doseq [handler (->> @(:subscriptions endpoint) vals (map type) (remove nil?))]
    (handler data)))

(defn- handle-reply
  [endpoint reply]
  (if-let [request (get @(:requests endpoint) (:request-id reply))]
    (do
      (js/clearTimeout (:timer request))
      ((fsafe ((if (= 200 (:status reply)) :success-fn :failure-fn) request))
       (select-keys reply [:status :payload])))
    (js/console.warn ":via/endpoint reply with invalid request-id" (:request-id reply))))

(defn- default-via-url
  []
  (when-let [location (.-location js/window)]
    (str "ws://" (.-host location) default-via-endpoint)))

(defn- append-query-params
  [url query-params]
  (->> query-params
       (map (fn [[k v]]
              (format "%s=%s"
                      (->string k)
                      (->string v))))
       (st/join "&") vector
       (filter seq)
       (cons url)
       (st/join "?")
       js/encodeURI))

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
