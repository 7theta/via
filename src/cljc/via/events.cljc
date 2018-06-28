;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
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

(declare handle-request)


;;; Public

(defmethod ig/init-key :via/events
  [_ {:keys [endpoint]}]
  {:endpoint endpoint
   :sub-key (subscribe endpoint {:message (partial handle-request endpoint)})})

(defmethod ig/halt-key! :via/events
  [_ {:keys [endpoint sub-key]}]
  (dispose endpoint sub-key))

(defn reg-event-via
  ([id handler]
   (reg-event-via id nil handler))
  ([id interceptors handler]
   (swap! handlers assoc id {:queue (-> [#'endpoint/interceptor]
                                        (concat interceptors)
                                        (concat [(via-interceptors/handler id handler)]))
                             :stack []})
   id))

;;; Implementation

(defn- handle-request
  [endpoint {:keys [payload request-id] :as request}]
  (if-let [context (get @handlers (first payload))]
    (-> context
        (merge {:event payload
                :request request}
               (select-keys request [:client-id :request-id]))
        interceptors/run)
    (throw
     (ex-info
      (str "Unhandled request: " (pr-str (:payload request)))
      {:payload (:payload request)}))))
