;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.events
  (:require [via.interceptors :as via-interceptors]
            [via.endpoint :refer [subscribe dispose send!] :as endpoint]
            [signum.interceptors :as interceptors]
            [utilis.fn :refer [fsafe]]
            [integrant.core :as ig]))

(defonce ^:private handlers (atom {}))

(declare handle-message)

;;; Public

(defmethod ig/init-key :via/events
  [_ {:keys [endpoint]}]
  {:endpoint endpoint
   :sub-key (subscribe endpoint {:message (partial handle-message endpoint)})})

(defmethod ig/halt-key! :via/events
  [_ {:keys [endpoint sub-key]}]
  (dispose endpoint sub-key))

(defn reg-event-via
  ([id handler]
   (reg-event-via id nil handler))
  ([id interceptors handler]
   (swap! handlers assoc id {:queue (concat [#'endpoint/interceptor] interceptors [(via-interceptors/handler id handler)])
                             :stack []})
   id))

;;; Implementation

(defn- handle-message
  [endpoint {:keys [payload] :as request}]
  (if-let [context (get @handlers (first payload))]
    (interceptors/run (assoc context :event payload :request request))
    (throw (ex-info (str ":via/events Unhandled request" (pr-str (:payload request))) {:request request}))))
