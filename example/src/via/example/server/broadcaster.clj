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
            [com.stuartsierra.component :as component]
            [clojure.core.async :refer [chan close! alts! timeout go-loop]]
            [taoensso.timbre :as log]))

(declare broadcast-loop)

;;; Types

(defrecord Broadcaster [client-proxy frequency]
  component/Lifecycle
  (start [component]
    (if-not (:control-ch component)
      (assoc component :control-ch (broadcast-loop client-proxy frequency))
      component))
  (stop [component]
    (if-let [ch (:control-ch component)]
      (do (close! ch)
          (dissoc component :control-ch))
      component)))

;;; Public

(defn broadcaster
  "Instantiates a broadcaster that will send a message to all connected
  clients ever 'frequency' seconds. 'frequency' defaults to 5 if not
  specified."
  ([] (broadcaster 5))
  ([frequency]
   (Broadcaster. nil frequency)))


;;; Implementation

(defn- broadcast-loop
  "Sends a message to all connected clients every 'frequency' number
  of seconds."
  [client-proxy frequency]
  (let [ch (chan)]
    (go-loop [i 0]
      (let [[v p] (alts! [ch (timeout (* 1000 frequency))])]
        (when-not (= p ch)
          (broadcast! client-proxy
                      [:via-example/server-broadcast
                       {:message "A periodic broadcast"
                        :frequency frequency
                        :index i}])
          (recur (inc i)))))
    ch))
