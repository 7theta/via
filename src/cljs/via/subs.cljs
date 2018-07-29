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
            [utilis.types.keyword :refer [->keyword]]
            [re-frame.core :refer [reg-sub-raw reg-event-db dispatch] :as re-frame]
            [reagent.ratom :refer [make-reaction]]
            [integrant.core :as ig]
            [clojure.data :refer [diff]]))

(defonce ^:private subscriptions (atom #{}))

(declare path via-subscribe via-dispose)

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint events]}]
  (add-watch subscriptions :via/subs
             (fn [_key _ref old-value new-value]
               (let [[removed added _] (diff old-value new-value)]
                 (doseq [s removed] (via-dispose endpoint s))
                 (doseq [s added] (via-subscribe endpoint s)))))
  (doseq [s @subscriptions] (via-subscribe endpoint s))
  {:endpoint endpoint
   :events events
   :sub-key (via/subscribe endpoint {:close #(doseq [s @subscriptions]
                                               (via-dispose endpoint s))})})

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint sub-key]}]
  (via/dispose endpoint sub-key))

(defn subscribe
  ([query-v]
   (subscribe query-v nil))
  ([query-v not-found]
   (let [[local-query-id & _ :as local-query-v]
         (update query-v 0 #(->> % str rest (apply str "via.") ->keyword))]
     (when-not (re-frame.registrar/get-handler :sub local-query-id)
       (reg-sub-raw
        local-query-id
        (fn [db local-query-v]
          (let [remote-query-v (assoc local-query-v 0 (first query-v))]
            (swap! subscriptions conj remote-query-v)
            (make-reaction
             #(get-in @db (path remote-query-v) not-found)
             :on-dispose #(swap! subscriptions disj remote-query-v))))))
     (re-frame/subscribe local-query-v))))

(reg-event-via
 :via.subs.db/updated
 (fn [_ [_ {:keys [query-v value]}]]
   (dispatch [:via.subs.db/write {:path (path query-v) :value value}])))

(reg-event-db
 :via.subs.db/write
 (fn [db [_ {:keys [path value]}]]
   (assoc-in db path value)))

(reg-event-db
 :via.subs.db/clear
 (fn [db [_ {:keys [path]}]]
   (update-in db (drop-last path) dissoc (last path))))

;;; Private

(defn- path
  [query-v]
  [:via.subs/cache query-v])

(defn- via-subscribe
  [endpoint query-v]
  (via/send! endpoint [:via.subs/subscribe {:query-v query-v
                                            :callback [:via.subs.db/updated]}]))

(defn- via-dispose
  [endpoint query-v]
  (via/send! endpoint [:via.subs/dispose {:query-v query-v}])
  (dispatch [:via.subs.db/clear {:path (path query-v)}]))
