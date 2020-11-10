(ns via.example.broadcaster
  (:require [via.endpoint :refer [broadcast!]]
            [tempus.core :as t]
            [clojure.core.async :refer [chan close! alts! timeout go-loop]]
            [integrant.core :as ig]))

;;; Public

(declare broadcaster)

(defmethod ig/init-key :via.example/broadcaster [_ {:keys [via-endpoint frequency]}]
  (broadcaster via-endpoint frequency))

(defmethod ig/halt-key! :via.example/broadcaster [_ {:keys [control-ch]}]
  (when control-ch
    (close! control-ch)))

(defn broadcaster
  "Instantiates a broadcaster that will send a event to all connected
  clients every `frequency` seconds"
  [via-endpoint frequency]
  (println "Starting broadcast loop")
  (let [ch (chan)]
    (go-loop [i 0]
      (let [[v p] (alts! [ch (timeout (* 1000 frequency))])]
        (if-not (= p ch)
          (let [msg [:via.example/server-broadcast {:event "A periodic broadcast"
                                                    :frequency frequency
                                                    :index i
                                                    :ts (t/now)}]]
            (println "Sending broadcast" (pr-str msg))
            (broadcast! via-endpoint msg)
            (recur (inc i)))
          (println "Shutting down broadcast loop"))))
    {:control-ch ch}))
