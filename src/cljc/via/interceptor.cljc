;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.interceptor)

;;; Declarations

(declare run-interceptors)

;;; Public

(defn ->interceptor
  [& {:keys [id before after]
      :or {before identity
           after identity}}]
  {:pre [(not (nil? id))]}
  {:id id
   :before before
   :after after})

(defn handler
  [id handler-fn]
  (->interceptor
   :id id
   :before #(let [effects (handler-fn (:coeffects %) (:event %))]
              (-> % (update :effects merge effects)
                  (assoc :status 200)))))

(defn run
  [context]
  (-> context
      (run-interceptors :forward)
      (run-interceptors :reverse)))

;;; Private

(defn- move-interceptor
  [context direction]
  (if (= direction :forward)
    (let [interceptor (first (:queue context))]
      (-> context (update :queue rest) (update :stack (partial cons interceptor))))
    (let [interceptor (first (:stack context))]
      (-> context (update :queue (partial cons interceptor)) (update :stack rest)))))

(defn- run-interceptor
  [context interceptor direction]
  (((if (= direction :forward) :before :after) interceptor) context))

(defn- run-interceptors
  [context direction]
  (loop [context context]
    (if-let [interceptor (first (get context (if (= direction :forward) :queue :stack)))]
      (recur (-> context
                 (move-interceptor direction)
                 (run-interceptor (cond-> interceptor (var? interceptor) deref) direction)))
      context)))
