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
            [via.defaults :refer [default-via-endpoint]]
            [haslett.client :as ws]
            [haslett.format :as fmt]
            [utilis.fn :refer [fsafe]]
            [cljs.core.async :as a :refer [go chan close! alt! <! >!]]
            [integrant.core :as ig]))

(declare connect! disconnect! send! default-via-url)

(def interceptor)

(defmethod ig/init-key :via/endpoint
  [_ {:keys [url auto-connect]
      :or {auto-connect true
           url (default-via-url)}}]
  (let [endpoint {:url url
                  :stream (atom nil)
                  :token (atom nil)
                  :subscriptions (atom {})
                  :control-ch (atom nil)
                  :requests (atom {})}]
    (set!
     interceptor
     (->interceptor
      :id :via.endpoint/interceptor
      :before #(update % :coeffects merge {:endpoint endpoint :request (:request %)})
      :after (fn [context]
               (when-let [response (get-in context [:effects :reply])]
                 (when (:request-id context)
                   (send! (fn [] endpoint) response
                          :type :reply
                          :client-id (:client-id context)
                          :params {:status (:status context)
                                   :request-id (:request-id context)})))
               context)))
    (when auto-connect (connect! (fn [] endpoint)))
    (fn [] endpoint)))

(defmethod ig/halt-key! :via/endpoint
  [_ endpoint]
  (disconnect! endpoint))

(declare handle-event handle-reply)

(defn connect!
  [endpoint]
  (let [endpoint (endpoint)]
    (when-not @(:stream endpoint)
      (let [stream (ws/connect (:url endpoint) {:format fmt/transit})
            control-ch (chan)]
        (go
          (let [stream (<! stream)]
            (handle-event endpoint :open {:status :initial})
            (loop []
              (alt!
                control-ch (ws/close stream)
                (:source stream) ([message]
                                  (if (nil? message)
                                    (handle-event endpoint :close {:status :server-closed})
                                    (do
                                      (case (:type message)
                                        :message (handle-event endpoint :message message)
                                        :reply (handle-reply endpoint message))
                                      (recur))))))))
        (reset! (:stream endpoint) stream)
        (when-let [old-control-ch @(:control-ch endpoint)] (close! old-control-ch))
        (reset! (:control-ch endpoint) control-ch)))))

(defn disconnect!
  [endpoint]
  (let [endpoint (endpoint)]
    (when @(:stream endpoint)
      (close! @(:control-ch endpoint))
      (reset! (:control-ch endpoint) nil)
      (reset! (:stream endpoint) nil)
      (handle-event endpoint :close {:status :normal}))))

(defn connected?
  [endpoint]
  (boolean @(:stream (endpoint))))

(defn subscribe
  [endpoint callbacks]
  (let [key (str (random-uuid))]
    (swap! (:subscriptions (endpoint)) assoc key callbacks)
    key))

(defn unsubscribe
  [endpoint key]
  (swap! (:subscriptions (endpoint)) dissoc key))

(defn send!
  [endpoint message & {:keys [type success-fn failure-fn timeout timeout-fn]
                       :or {type :message}}]
  {:pre [(if-not (nil? success-fn) (and (number? timeout) timeout-fn failure-fn) (nil? timeout))]}
  (let [endpoint (endpoint)
        do-send (fn [message params]
                  (if-let [stream @(:stream endpoint)]
                    (go (>! (:sink (<! stream))
                            (merge {:type type
                                    :token @(:token endpoint)
                                    :payload message} params)))
                    (throw (js/Error. ":via.client/endpoint - not connected"))))]
    (if-not success-fn
      (do-send message {})
      (let [request-id (str (random-uuid))]
        (swap! (:requests endpoint)
               assoc request-id {:success-fn success-fn
                                 :failure-fn failure-fn
                                 :message message
                                 :timer (js/setTimeout (fsafe timeout-fn) timeout)})
        (do-send message {:request-id request-id
                          :timeout timeout})))))

(defn authenticate!
  [endpoint {:keys [id password]} & {:keys [success-fn failure-fn timeout timeout-fn]}]
  (send! endpoint {:id id :password password}
         :type :authentication-request
         :success-fn (fn [{:keys [payload] :as reply}]
                       (reset! (:token (endpoint)) (:token payload))
                       (handle-event (endpoint) :auth-open {:status :ok
                                                            :user payload})
                       (success-fn payload))
         :failure-fn (fn [{:keys [payload] :as reply}]
                       (reset! (:token (endpoint)) nil)
                       (handle-event (endpoint) :auth-close payload)
                       (failure-fn (:error payload)))
         :timeout timeout
         :timeout-fn #(do (handle-event (endpoint) :auth-close {:status :timeout}) (timeout-fn))))

(defn- handle-event
  [endpoint type data]
  (doseq [handler (->> @(:subscriptions endpoint) vals (map type) (remove nil?))]
    (handler data)))

(defn- handle-reply
  [endpoint reply]
  (when-let [request (get @(:requests endpoint) (:request-id reply))]
    (js/clearTimeout (:timer request))
    (((if (= 200 (:status reply)) :success-fn :failure-fn) request)
     (select-keys reply [:status :payload]))))

(defn- default-via-url
  []
  (when-let [location (.-location js/window)]
    (str "ws://" (.-host location) default-via-endpoint)))
