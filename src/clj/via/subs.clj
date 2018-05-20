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
  (:require [via.interceptor :as interceptor]
            [via.events :refer [reg-event-via]]
            [via.endpoint :as via]
            [integrant.core :as ig]))

(defonce ^:private handlers (atom {}))
(defonce ^:private subscriptions (atom {}))

(declare subscribe unsubscribe)

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint events]}]
  {:endpoint endpoint
   :events events
   :sub-key (via/subscribe endpoint {:close (fn [{:keys [client-id]}]
                                              (unsubscribe endpoint client-id))})})

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint sub-key]}]
  (via/unsubscribe endpoint sub-key)
  (doseq [[sub-v client-id] (keys @subscriptions)]
    (unsubscribe endpoint client-id sub-v)))

(defn reg-sub-via
  ([id sub-fn dispose-fn]
   (reg-sub-via id nil sub-fn dispose-fn))
  ([id interceptors sub-fn dispose-fn]
   (swap! handlers assoc id
          {:dispose-fn dispose-fn
           :queue (-> [#'via/interceptor]
                      (concat interceptors)
                      (concat [(interceptor/handler
                                id (fn [coeffects _]
                                     (let [{:keys [endpoint client-id sub-v callback]} coeffects
                                           coeffects (dissoc coeffects :sub-v :callback)]
                                       {::context
                                        (sub-fn (merge
                                                 coeffects
                                                 {:callback #(try
                                                               (via/send! endpoint (conj (vec callback)
                                                                                         {:sub-v sub-v :value %})
                                                                          :client-id client-id)
                                                               true
                                                               (catch Exception _
                                                                 (unsubscribe endpoint client-id)
                                                                 false))})
                                                sub-v)})))]))
           :stack []})))

(reg-event-via
 :via.subs/subscribe
 (fn [context [_ {:keys [sub-v callback]}]]
   {:reply (if (subscribe context sub-v callback)
             {:status :success}
             {:status :error
              :error :invalid-subscription
              :sub-v sub-v})}))

(reg-event-via
 :via.subs/unsubscribe
 (fn [{:keys [endpoint client-id]} [_ {:keys [sub-v]}]]
   {:reply (if (unsubscribe endpoint client-id sub-v)
             {:status :success}
             {:status :error
              :error :invalid-subscription
              :sub-v sub-v})}))

(defn subscribe
  [{:keys [endpoint ring-request request client-id] :as event-context} sub-v callback]
  (when-let [{:keys [sub-fn] :as sub-context} (get @handlers (first sub-v))]
    (let [result (-> (merge {:endpoint endpoint
                             :request (assoc request :ring-request ring-request)
                             :client-id client-id
                             :coeffects {:sub-v sub-v
                                         :callback callback}}
                            sub-context)
                     interceptor/run)]
      (boolean
       (when-let [result-context (-> result :effects ::context)]
         (swap! subscriptions assoc [sub-v client-id] result-context)
         true)))))

(defn unsubscribe
  ([endpoint client-id]
   (doseq [[sub-v _] (->> @subscriptions keys
                          (filter #(= client-id (second %))))]
     (unsubscribe endpoint client-id sub-v)))
  ([endpoint client-id sub-v]
   (when-let [context (get @subscriptions [sub-v client-id])]
     ((get-in @handlers [(first sub-v) :dispose-fn]) context)
     (swap! subscriptions dissoc [sub-v client-id])
     true)))
