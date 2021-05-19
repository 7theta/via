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
            [reagent.ratom :as ra]
            [re-frame.core :as rf]))

(defn subscribe
  [endpoint peer-id [query-id & _ :as query-v] default]
  (let []
    (when-not (re-frame.registrar/get-handler :sub query-id)
      (let [sub (vs/subscribe endpoint peer-id query-v default)]

        (add-watch sub ::subscribe
                   (fn [_ _ _ value]

                     (println
                      {:query-v query-v
                       :value value
                       :default default})

                     ))

        (rf/reg-sub
         query-id
         (fn [db query-v]
           default
           #_(swap! subscriptions conj query-v)
           #_(get-in db (path query-v))))))
    (ra/make-reaction #(let [sub-value @(rf/subscribe query-v)]
                         sub-value
                         #_(if (get @subscriptions query-v)
                             (if (:updated sub-value) (:value sub-value) default)
                             (if (nil? sub-value) default sub-value))))))

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
