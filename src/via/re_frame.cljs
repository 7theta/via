;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.re-frame
  (:require [via.endpoint :as via]
            [via.defaults :as defaults]
            [via.subs :as vs]
            [via.events :as ve]
            [via.adapter :as va]
            [reagent.ratom :as ra]
            [re-frame.core :as rf]
            [re-frame.registrar :as rfr]
            [clojure.data :refer [diff]]))

(declare path subscriptions re-frame-handlers)

(defn subscribe
  [endpoint peer-id [query-id & _ :as query-v] default]
  (let [subscriptions (subscriptions endpoint peer-id)
        remote? (fn [query-v] (get @subscriptions query-v))]
    (when-not (re-frame.registrar/get-handler :sub query-id)
      (rf/reg-sub
       query-id
       (fn [db query-v]
         (swap! subscriptions conj query-v)
         (get-in db (path query-v)))))
    (ra/make-reaction #(let [sub-value @(rf/subscribe query-v)]
                         (cond
                           (remote? query-v) sub-value
                           (nil? sub-value) default
                           :else sub-value)))))

(defn dispatch
  [endpoint peer-id event options]
  (if (re-frame.registrar/get-handler :event (first event))
    (rf/dispatch event)
    (ve/dispatch endpoint peer-id event (re-frame-handlers options))))

;;; Effect Handlers

(rf/reg-fx
 :via/dispatch
 (fn [request-map-or-seq]
   ;;  Registers an effects handler that will dispatch requests to the server
   ;; referred to by `endpoint` using via. An optional `timeout` can
   ;; be provided which will be used for requests if no :timeout is
   ;; provided in the individual request.

   ;; The requests can be provided as a sequence or a single map of the
   ;; following form:

   ;;   {:event <re-frame-via event registered on the server>
   ;;    :on-reply <re-frame event to dispatch on reply from the server>
   ;;    :on-timeout <re-frame event to dispatch on error>
   ;;    :timeout <optional timeout in ms>
   ;;    :late-reply <a boolean indicating whether a late reply received
   ;;                 after the timeout should be delivered. Defaults to false>}

   ;; The :on-reply and :on-timeout can be omitted for one-way events to the server.
   ;; However if a reply from the server is expected, both must be provided.
   ;; Additionally all requests that expect a reply from the server must have
   ;; a timeout, which can be provided when the effects handler is registered and
   ;; overridden in an individual request.
   (doseq [{:keys [event
                   timeout
                   on-success
                   on-failure
                   on-timeout
                   endpoint
                   peer-id] :as request}
           (if (sequential? request-map-or-seq) request-map-or-seq [request-map-or-seq])]
     (let [endpoint (or endpoint (first @via/endpoints))
           peer-id (or peer-id (via/first-peer endpoint))]
       (if on-success
         (via/send endpoint peer-id event
                   :on-success (when on-success #(rf/dispatch (conj (vec on-success) (:payload %))))
                   :on-failure (when on-failure #(rf/dispatch (conj (vec on-failure) (:payload %))))
                   :on-timeout (when on-timeout #(rf/dispatch (conj (vec on-timeout) (:payload %))))
                   :timeout (or timeout defaults/request-timeout))
         (via/send endpoint peer-id event))))))

;;; Event Handlers

(rf/reg-event-fx
 :via/dispatch
 (fn [_ request-map-or-seq]
   {:via/dispatch request-map-or-seq}))

(rf/reg-event-fx
 :via.session-context/replace
 (fn [_ session-context]
   {:via/dispatch {:event [:via.session-context/replace {:session-context session-context
                                                         :sync false}]}}))

(rf/reg-event-fx
 :via.session-context/merge
 (fn [_ session-context]
   {:via/dispatch {:event [:via.session-context/merge {:session-context session-context
                                                       :sync false}]}}))

;;; Implementation

(defn- path
  [query-v]
  [:via.subs/cache query-v])

(rf/reg-event-fx
 :via.re-frame.sub.value/updated
 (fn [{:keys [db]} [_ query-v value]]
   {:db (assoc-in db (path query-v) value)}))

(defn remote-subscribe
  [endpoint peer-id remote-subscriptions query-v]
  (let [sub (vs/subscribe endpoint peer-id query-v)]
    (add-watch sub ::remote-subscribe
               (fn [_ _ _ value]
                 (rf/dispatch [:via.re-frame.sub.value/updated query-v value])))
    (swap! remote-subscriptions assoc query-v sub)
    sub))

(defn remote-dispose
  [endpoint peer-id remote-subscriptions query-v]
  (when-let [sub (get @remote-subscriptions query-v)]
    (remove-watch sub ::remote-subscribe)
    (swap! remote-subscriptions dissoc query-v)
    (vs/dispose endpoint peer-id query-v)))

(defn subscriptions
  [endpoint peer-id]
  (let [context (va/context (endpoint))]
    (or (::subscriptions @context)
        (let [subscriptions (atom #{})
              remote-subscriptions (atom {})]
          (swap! context assoc ::subscriptions subscriptions)
          (add-watch subscriptions ::subscriptions
                     (fn [_key _ref old-value new-value]
                       (let [[removed added _] (diff old-value new-value)]
                         (doseq [query-v removed]
                           (when-not (->> @subscriptions (filter #(= (first %) (first query-v))) not-empty)
                             (rfr/clear-handlers :sub (first query-v)))
                           (remote-dispose endpoint peer-id remote-subscriptions query-v))
                         (doseq [query-v added]
                           (remote-subscribe endpoint peer-id remote-subscriptions query-v)))))
          subscriptions))))

(defn re-frame-handlers
  [options]
  (reduce (fn [options key]
            (if-let [handler (get options key)]
              (assoc options key
                     (if (vector? handler)
                       (fn [& args]
                         (rf/dispatch
                          (vec (concat handler args))))
                       handler))
              options))
          options
          [:on-success
           :on-failure
           :on-timeout]))
