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
  [id sub-fn dispose-fn]
  (swap! handlers assoc id {:sub-fn sub-fn
                            :dispose-fn dispose-fn}))

(reg-event-via
 :via.subs/subscribe
 (fn [{:keys [endpoint client-id]} [_ {:keys [sub-v callback]}]]
   {:reply (if (subscribe endpoint client-id sub-v callback)
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
  [endpoint client-id sub-v callback]
  (when-let [{:keys [sub-fn]} (get @handlers (first sub-v))]
    (let [context (sub-fn sub-v #(try
                                   (via/send! endpoint (conj (vec callback)
                                                             {:sub-v sub-v :value %})
                                              :client-id client-id)
                                   true
                                   (catch Exception _
                                     (unsubscribe endpoint client-id)
                                     false)))]
      (swap! subscriptions assoc [sub-v client-id] context)
      true)))

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
