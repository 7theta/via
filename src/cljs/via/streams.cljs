;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.streams
  (:require [via.events :refer [reg-event-via]]
            [via.endpoint :as via]
            [re-frame.core :refer [reg-sub-raw
                                   reg-event-db
                                   reg-event-fx
                                   dispatch]]
            [reagent.ratom :refer [make-reaction]]
            [integrant.core :as ig]
            [clojure.data :refer [diff]]))

(defonce ^:private streams (atom #{}))
(defonce ^:private rfs (atom {}))

(declare path subscribe unsubscribe)

;;; Public

(defmethod ig/init-key :via/streams
  [_ {:keys [endpoint events]}]
  (doseq [s @streams] (subscribe endpoint s))
  (add-watch streams :via/streams
             (fn [_key _ref old-value new-value]
               (let [[removed added _] (diff old-value new-value)]
                 (doseq [s removed] (unsubscribe endpoint s))
                 (doseq [s added] (subscribe endpoint s)))))
  {:endpoint endpoint
   :events events})

(defmethod ig/halt-key! :via/streams
  [_ {:keys [endpoint]}])

(defn reg-stream-via
  "Applies the reducing function 'rf' over the values as they come in off the
  corresponding stream represented by 'id' on the server."
  [id rf]
  {:pre [(and (keyword? id) (fn? rf))]}
  (let [has-disposed? (atom false)]
    (reg-sub-raw
     id (fn [db sub-v]
          (swap! streams conj sub-v)
          (swap! rfs assoc sub-v rf)
          (make-reaction
           #(get-in @db (conj (path sub-v) :value))
           :on-dispose #(when-not @has-disposed?
                          (reset! has-disposed? true)
                          (swap! streams disj sub-v)
                          (swap! rfs dissoc sub-v)))))))

(defn reg-acc-via
  "Accumulates values as they come off the stream 'id', individually subject to
  transform function 'tx-fn'."
  ([id] (reg-acc-via id identity))
  ([id tx-fn]
   (reg-stream-via
    id (fn [sq new-value]
         (conj (vec sq)
               (tx-fn new-value))))))

(reg-event-via
 :via.streams.db/on-receive
 (fn [db [_ {:keys [sub-v value] :as message}]]
   (dispatch
    [:via.streams.db/append
     {:path (path sub-v)
      :value value
      :sub-v sub-v}])))

(reg-event-fx
 :via.streams.db/append
 (fn [{:keys [db]} [_ {:keys [path value sub-v]}]]
   {:db (update-in db (conj path :to-reduce) #(conj (or % []) value))
    :dispatch [:via.streams.db/reduce sub-v]}))

(reg-event-db
 :via.streams.db/reduce
 (fn [db [_ sub-v]]
   (let [path (path sub-v)
         to-reduce-path (conj path :to-reduce)
         to-reduce (get-in db to-reduce-path)
         value-path (conj path :value)
         value (get-in db value-path)
         rf (get @rfs sub-v)
         new-value (reduce rf value to-reduce)]
     (-> db
         (assoc-in value-path new-value)
         (assoc-in to-reduce-path [])))))

(reg-event-db
 :via.streams.db/clear
 (fn [db [_ {:keys [path]}]]
   (update-in db (drop-last path) dissoc (last path))))

;;; Private

(defn- path
  [sub-v]
  [:via/streams :cache sub-v])

(defn- subscribe
  [endpoint sub-v]
  (via/send!
   endpoint
   [:via.streams/subscribe
    {:sub-v sub-v
     :callback [:via.streams.db/on-receive]}]))

(defn- unsubscribe
  [endpoint sub-v]
  (via/send! endpoint [:via.streams/unsubscribe {:sub-v sub-v}])
  (dispatch [:via.streams.db/clear {:path (path sub-v)}]))
