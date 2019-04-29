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
  (:require [via.events :refer [reg-event-via]]
            [via.endpoint :as via]
            [signum.subs :as signum]
            [distantia.core :refer [diff]]
            [integrant.core :as ig]))

(defonce ^:private subscriptions (atom {}))

(declare subscribe dispose value-handler)

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint]}]
  {:endpoint endpoint
   :sub-key (via/subscribe endpoint {:close (fn [{:keys [client-id]}]
                                              (dispose endpoint client-id))})})

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint sub-key]}]
  (doseq [[query-v client-id] (keys @subscriptions)]
    (dispose endpoint client-id query-v))
  (via/dispose endpoint sub-key))

(reg-event-via
 :via.subs/subscribe
 (fn [{:keys [endpoint request]} [_ {:keys [query-v callback]}]]
   (if (subscribe endpoint request query-v callback)
     {:via/status 200
      :via/reply {:status :success}}
     {:via/status 400
      :via/reply {:status :error
                  :error :invalid-subscription
                  :query-v query-v}})))

(reg-event-via
 :via.subs/dispose
 (fn [{:keys [endpoint request]} [_ {:keys [query-v]}]]
   (if (dispose endpoint (:client-id request) query-v)
     {:via/status 200
      :via/reply {:status :success}}
     {:via/status 400
      :via/reply {:status :error
                  :error :invalid-subscription
                  :query-v query-v}})))

;;; Private

(defn- subscribe
  [endpoint request [query-id & _ :as query-v] callback]
  (when (= #'via/interceptor (first (signum/interceptors query-id)))
    (when-let [signal (binding [signum/*context* {:endpoint endpoint :request request}]
                        (signum/subscribe query-v))]
      (let [client-id (:client-id request)
            watch-key (str ":via-" query-v "(" client-id ")")
            send-value! #(try
                           (via/send! endpoint (conj (vec callback) {:query-v query-v :change %})
                                      :client-id client-id)
                           true
                           (catch Exception e
                             (println :via/send-value "->" client-id "\n" e)
                             (dispose endpoint client-id)
                             false))]
        (swap! subscriptions assoc [query-v client-id] {:signal signal :watch-key watch-key})
        (add-watch signal watch-key (fn [_ _ old new]
                                      (when (not= old new)
                                        (send-value! (if (or (and (map? new) (map? old))
                                                             (and (vector? new) (vector? old)))
                                                       [:p (diff old new)]
                                                       [:v new])))))
        (send-value! [:v @signal])))))

(defn- dispose
  ([endpoint client-id]
   (doseq [[query-v _] (->> @subscriptions keys (filter #(= client-id (second %))))]
     (dispose endpoint client-id query-v)))
  ([endpoint client-id query-v]
   (when-let [{:keys [signal watch-key]} (get @subscriptions [query-v client-id])]
     (remove-watch signal watch-key)
     (signum/dispose signal)
     (swap! subscriptions dissoc [query-v client-id])
     true)))
