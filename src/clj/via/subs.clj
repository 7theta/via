;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.subs
  (:require [via.interceptors :as via-interceptor]
            [via.events :refer [reg-event-via]]
            [via.endpoint :as via]
            [signum.subs :as signum]
            [integrant.core :as ig]))

(defonce ^:private subscriptions (atom {}))

(declare subscribe dispose value-handler)

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint]}]
  {:endpoint endpoint
   :sub-key (via/subscribe
             endpoint
             {:close (fn [{:keys [client-id]}]
                       (dispose endpoint client-id))})})

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint sub-key]}]
  (via/dispose endpoint sub-key)
  (doseq [[query-v client-id] (keys @subscriptions)]
    (dispose endpoint client-id query-v)))

(reg-event-via
 :via.subs/subscribe
 (fn [{:keys [endpoint ring-request request client-id]} [_ {:keys [query-v callback]}]]
   {:via/reply
    (if (subscribe {:endpoint endpoint
                    :request (assoc request :ring-request ring-request)
                    :client-id client-id}
                   query-v callback)
      {:status :success}
      {:status :error
       :error :invalid-subscription
       :query-v query-v})}))

(reg-event-via
 :via.subs/dispose
 (fn [{:keys [endpoint client-id]} [_ {:keys [query-v]}]]
   {:via/reply
    (if (dispose endpoint client-id query-v)
      {:status :success}
      {:status :error
       :error :invalid-subscription
       :query-v query-v})}))

;;; Private

(defn- subscribe
  [{:keys [endpoint client-id] :as event-context} [query-id & _ :as query-v] callback]
  (if-let [signal (signum/subscribe query-v event-context)]
    (let [watch-key (keyword (str query-v client-id))
          send-value! #(try
                         (via/send! endpoint
                                    (conj (vec callback)
                                          {:query-v query-v
                                           :value %})
                                    :client-id client-id)
                         true
                         (catch Exception _
                           (dispose endpoint client-id)
                           false))]
      (swap! subscriptions assoc [query-v client-id] {:signal signal
                                                      :watch-key watch-key})
      (add-watch signal watch-key (fn [_ _ old new] (when (not= old new) (send-value! new))))
      (send-value! @signal))
    (throw (ex-info ":via.subs/subscribe Invalid Query" {:query-v query-v}))))

(defn- dispose
  ([endpoint client-id]
   (doseq [[query-v _] (->> @subscriptions keys (filter #(= client-id (second %))))]
     (dispose endpoint client-id query-v)))
  ([endpoint client-id query-v]
   (when-let [subscription (get @subscriptions [query-v client-id])]
     (signum/dispose (:signal subscription))
     (swap! subscriptions dissoc [query-v client-id])
     true)))
