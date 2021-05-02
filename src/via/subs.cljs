;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.subs
  (:require [via.events :refer [reg-event-via]]
            [via.endpoint :as via]
            [distantia.core :refer [patch]]
            [utilis.fn :refer [fsafe]]
            [utilis.types.keyword :refer [->keyword]]
            [re-frame.core :refer [reg-sub reg-event-db dispatch] :as re-frame]
            [re-frame.subs :refer [query->reaction]]
            [reagent.ratom :refer [make-reaction]]
            [integrant.core :as ig]
            [clojure.data :refer [diff]]))

(defonce ^:private subscriptions (atom #{}))

(declare path remote-subscribe remote-dispose)

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint]}]
  (add-watch subscriptions :via.subs/subscriptions
             (fn [_key _ref old-value new-value]
               (let [[removed added _] (diff old-value new-value)]
                 (doseq [query-v removed]
                   (when-not (->> @subscriptions (filter #(= (first %) (first query-v))) not-empty)
                     (re-frame.registrar/clear-handlers :sub (first query-v)))
                   (remote-dispose endpoint query-v))
                 (doseq [query-v added]
                   (remote-subscribe endpoint query-v)))))
  (add-watch query->reaction :via.subs/subscription-cache
             (fn [_key _ref old-value new-value]
               (reset! subscriptions (->> new-value keys (map first) (filter @subscriptions) set))))
  {:endpoint endpoint
   :sub-key (via/subscribe endpoint {:open #(doseq [query-v @subscriptions]
                                              (remote-subscribe endpoint query-v))})})

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint sub-key]}]
  (remove-watch subscriptions :via.subs/subscriptions)
  (remove-watch query->reaction :via.subs/subscription-cache)
  (doseq [query-v @subscriptions] (remote-dispose endpoint query-v))
  (via/dispose endpoint sub-key))

(defn subscribe
  ([query-v] (subscribe query-v nil))
  ([[query-id & _ :as query-v] default]
   (let []
     (when-not (re-frame.registrar/get-handler :sub query-id)
       (reg-sub
        query-id
        (fn [db query-v]
          (swap! subscriptions conj query-v)
          (get-in db (path query-v)))))
     (make-reaction #(let [sub-value @(re-frame/subscribe query-v)]
                       (if (get @subscriptions query-v) ; remote subscription
                         (if (:updated sub-value) (:value sub-value) default)
                         (if (nil? sub-value) default sub-value)))))))

;;; Private

(defn- path
  [query-v]
  [:via.subs/cache query-v])

(reg-event-via
 :via.subs.db/updated
 (fn [_ [_ {:keys [query-v change sn]}]]
   (dispatch [:via.subs.db/write {:path (path query-v)
                                  :change change
                                  :sn sn}])))

(defn- write-message
  [db {:keys [path change sn]}]
  (let [now (js/Date.)]
    (-> (if (= :v (first change))
          (assoc-in db (conj path :value) (second change))
          (update-in db (conj path :value) patch (second change)))
        (assoc-in (conj path :updated) (.getTime (js/Date.)))
        (assoc-in (conj path :last-sn) sn))))

(defn- split-contiguous
  [last-sn window]
  (let [window (sort-by :sn window)
        state (volatile! last-sn)
        result (partition-by #(= (:sn %) (vswap! state inc)) window)]
    (cond
      (= (count result) 2) result
      (= (inc last-sn) (:sn (ffirst result))) [(first result) nil]
      :else [nil (first result)])))

(reg-event-db
 :via.subs.db/write
 (fn [db [_ {:keys [path sn] :as msg}]]
   (let [{:keys [window last-sn]
          :or {last-sn 0}} (get-in db path)
         [contiguous-messages window] (->> msg
                                           (conj window)
                                           (split-contiguous last-sn))]
     (as-> db %
       (assoc-in % (conj path :window) window)
       (reduce write-message % contiguous-messages)))))

(reg-event-db
 :via.subs.db/clear
 (fn [db [_ {:keys [path]}]]
   (update-in db (drop-last path) dissoc (last path))))

(defn- remote-subscribe
  [endpoint query-v]
  (via/send! endpoint [:via.subs/subscribe {:query-v query-v
                                            :callback [:via.subs.db/updated]}]
             :failure-fn #(js/console.warn ":via.subs/subscribe" (pr-str query-v) "failed" (pr-str %))))

(defn- remote-dispose
  [endpoint query-v]
  (via/send! endpoint [:via.subs/dispose {:query-v query-v}]
             :failure-fn #(js/console.warn ":via.subs/dispose" (pr-str query-v) "failed" (pr-str %)))
  (dispatch [:via.subs.db/clear {:path (path query-v)}]))
