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
            [re-frame.core :refer [reg-sub-raw reg-event-db dispatch]]
            [reagent.ratom :refer [make-reaction]]
            [integrant.core :as ig]
            [clojure.data :refer [diff]]))

(defonce ^:private subscriptions (atom #{}))

(declare path subscribe unsubscribe)

;;; Public

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint events]}]
  (doseq [s @subscriptions] (subscribe endpoint s))
  (add-watch subscriptions :via/subs
             (fn [_key _ref old-value new-value]
               (let [[removed added _] (diff old-value new-value)]
                 (doseq [s removed] (unsubscribe endpoint s))
                 (doseq [s added] (subscribe endpoint s)))))
  {:endpoint endpoint
   :events events
   :sub-key (via/subscribe endpoint {:close #(doseq [s @subscriptions]
                                               (unsubscribe endpoint s))})})

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint sub-key]}]
  (via/unsubscribe endpoint sub-key))

(defn reg-sub-via
  [id sub-fn]
  (reg-sub-raw
   id
   (fn [db sub-v]
     (swap! subscriptions conj sub-v)
     (make-reaction
      #(sub-fn (get-in @db (path sub-v)))
      :on-dispose #(swap! subscriptions disj sub-v)))))

(reg-event-via
 :via.subs.db/updated
 (fn [_ [_ {:keys [sub-v value] :as message}]]
   (dispatch [:via.subs.db/write {:path (path sub-v) :value value}])))

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
  [sub-v]
  [:via/subs :cache sub-v])

(defn- subscribe
  [endpoint sub-v]
  (via/send! endpoint [:via.subs/subscribe {:sub-v sub-v
                                            :callback [:via.subs.db/updated]}]))

(defn- unsubscribe
  [endpoint sub-v]
  (via/send! endpoint [:via.subs/unsubscribe {:sub-v sub-v}])
  (dispatch [:via.subs.db/clear {:path (path sub-v)}]))
