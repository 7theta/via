;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.fx
  (:require [via.endpoint :as via]
            [re-frame.core :refer [reg-fx dispatch]]
            [integrant.core :as ig]))

(declare register)

;;; Public

(def default-timeout 5000)

(defmethod ig/init-key :via/fx
  [_ {:keys [endpoint timeout]}]
  (register endpoint :timeout timeout))

(defn register
  "Registers an effects handler that will dispatch requests to the server
  referred to by `endpoint` using via. An optional `timeout` can
  be provided which will be used for requests if no :timeout is
  provided in the individual request.

  The requests can be provided as a sequence or a single map of the
  following form:

    {:event <re-frame-via event registered on the server>
     :on-reply <re-frame event to dispatch on reply from the server>
     :on-timeout <re-frame event to dispatch on error>
     :timeout <optional timeout in ms>
     :late-reply <a boolean indicating whether a late reply received
                  after the timeout should be delivered. Defaults to false>}

  The :on-reply and :on-timeout can be omitted for one-way events to the server.
  However if a reply from the server is expected, both must be provided.
  Additionally all requests that expect a reply from the server must have
  a timeout, which can be provided when the effects handler is registered and
  overridden in an individual request."
  [endpoint & {:keys [timeout]
               :or {timeout default-timeout}}]
  (let [default-timeout (or timeout default-timeout)]
    (reg-fx
     :via/dispatch
     (fn [request-map-or-seq]
       (doseq [{:keys [event timeout on-success on-failure on-timeout] :as request}
               (if (sequential? request-map-or-seq) request-map-or-seq [request-map-or-seq])]
         (if on-success
           (via/send! endpoint event
                      :timeout (or timeout default-timeout)
                      :success-fn #(dispatch (conj (vec on-success) (:payload %)))
                      :failure-fn #(dispatch (conj (vec on-failure) (:payload %)))
                      :timeout-fn (if on-timeout
                                    #(dispatch (conj (vec on-timeout) (:payload %)))
                                    #(js/console.warn ":via/dispatch timeout" event)))
           (via/send! endpoint event)))))))
