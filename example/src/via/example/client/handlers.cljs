;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.example.client.handlers
  (:require [taoensso.timbre :as log]))

;;; Public

(defmulti msg-handler :id)

(defmethod msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (log/debug "Channel socket successfully established!")
    (log/debug "Channel socket state change: " ?data)))

(defmethod msg-handler :chsk/recv
  [{:as ev-msg :keys [?data]}]
  (log/debug "Push event from server: " ?data))

(defmethod msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (let [[?uid ?csrf-token ?handshake-data] ?data]
    (log/debug "Handshake: " ?data)))

(defmethod msg-handler :default
  [{:as ev-msg :keys [event]}]
  (log/warn "Unhandled event: " event))
