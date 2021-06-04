(ns via.example.events
  (:require [signum.events :as se]))

(defonce counter (atom 0))

(se/reg-event
 :api.example/increment-count
 (fn [_ _]
   {:via/reply {:body (swap! counter inc)
                :status 200}}))
