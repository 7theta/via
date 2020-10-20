(ns via.example.events
  (:require [via.events :refer [reg-event-via]]))

(defonce counter (atom 0))

(reg-event-via
 :api.example/increment-count
 []
 (fn [_ _]
   {:via/reply (swap! counter inc)
    :via/status 200}))
