;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.subs
  (:require [via.endpoint :as via]
            [via.adapter :as adapter]
            [signum.events :as se]
            [signum.subs :as ss]
            [distantia.core :refer [diff]]
            [integrant.core :as ig]))

(declare subscribe dispose)

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint]}]
  (let [subscriptions (atom {})]
    (via/merge-context endpoint {::subscriptions subscriptions})
    (via/reg-event endpoint :via.subs/subscribe)
    (via/reg-event endpoint :via.subs/dispose)
    {:endpoint endpoint
     :subscriptions subscriptions
     :listener-id (via/add-event-listener endpoint
                                          :close (fn [{:keys [peer-id]}]
                                                   (dispose endpoint peer-id)))}))

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint listener-id subscriptions]}]
  (doseq [[query-v peer-id] (keys @subscriptions)]
    (dispose endpoint peer-id query-v))
  (via/remove-event-listener endpoint :close listener-id))

(se/reg-event
 :via.subs/subscribe
 (fn [{:keys [endpoint request]} [_ {:keys [query-v callback]}]]
   (if (subscribe endpoint request query-v callback)
     {:via/status 200
      :via/reply {:body {:status :success}
                  :status 200}}
     {:via/reply {:status 400
                  :body {:status :error
                         :error :invalid-subscription
                         :query-v query-v}}})))

(se/reg-event
 :via.subs/dispose
 (fn [{:keys [endpoint request]} [_ {:keys [query-v]}]]
   (if (dispose endpoint (:peer-id request) query-v)
     {:via/reply {:status 200
                  :body {:status :success}}}
     {:via/reply {:status 400
                  :body {:status :error
                         :error :invalid-subscription
                         :query-v query-v}}})))

;;; Private

(defn- subscribe
  [endpoint request [query-id & _ :as query-v] callback]
  (when-let [signal (binding [ss/*context* {:endpoint endpoint
                                            :request request}]
                      (ss/subscribe query-v))]
    (let [peer-id (:peer-id request)
          sequence-number (atom (long 0))
          watch-key (str ":via-" query-v "(" peer-id ")")
          send-value! #(try (via/send endpoint (conj (vec callback)
                                                     {:query-v query-v
                                                      :change %
                                                      :sn (swap! sequence-number inc)})
                                      :peer-id peer-id)
                            true
                            (catch Exception e
                              (println :via/send-value "->" peer-id "\n" e)
                              (dispose endpoint peer-id)
                              false))]
      (swap! (::subscriptions @(adapter/context (endpoint)))
             assoc [query-v peer-id] {:signal signal
                                      :watch-key watch-key})
      (add-watch signal watch-key (fn [_ _ old new]
                                    (when (not= old new)
                                      (send-value! (if (or (and (map? new) (map? old))
                                                           (and (vector? new) (vector? old)))
                                                     [:p (diff old new)]
                                                     [:v new])))))
      (send-value! [:v @signal]))))

(defn- dispose
  ([endpoint peer-id]
   (doseq [[query-v _] (->> @(::subscriptions @(adapter/context (endpoint)))
                            keys
                            (filter #(= peer-id (second %))))]
     (dispose endpoint peer-id query-v)))
  ([endpoint peer-id query-v]
   (let [subscriptions (::subscriptions @(adapter/context (endpoint)))]
     (when-let [{:keys [signal watch-key]} (get @subscriptions [query-v peer-id])]
       (remove-watch signal watch-key)
       (swap! subscriptions dissoc [query-v peer-id])
       true))))
