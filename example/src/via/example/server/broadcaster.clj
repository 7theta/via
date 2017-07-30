;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.example.server.broadcaster
  (:require [via.server.client-proxy :refer [broadcast!]]
            [clojure.core.async :refer [chan close! alts! timeout go-loop]]
            [integrant.core :as ig]
            [taoensso.timbre :as log]))

;;; Public

(declare broadcaster)

(defmethod ig/init-key ::broadcaster [_ {:keys [client-proxy frequency]}]
  (broadcaster client-proxy frequency))

(defmethod ig/halt-key! ::broadcaster [_ {:keys [control-ch]}]
  (when control-ch
    (close! control-ch)))

(defn broadcaster
  "Instantiates a broadcaster that will send a message to all connected
  clients ever 'frequency' seconds"
  [client-proxy frequency]
  (let [ch (chan)]
    (go-loop [i 0]
      (let [[v p] (alts! [ch (timeout (* 1000 frequency))])]
        (if-not (= p ch)
          (let [msg [:via-example/server-broadcast
                     {:message "A periodic broadcast"
                      :frequency frequency
                      :index i}]]
            (log/debug "Sending broadcast" (pr-str msg))
            (broadcast! client-proxy msg)
            (recur (inc i)))
          (log/debug "Shutting down broadcast loop"))))
    {:control-ch ch}))
