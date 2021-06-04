(ns via.example.events
  (:require [signum.events :as se]))

(se/reg-event
 :api.example/echo
 (fn [_ [_ value]]
   {:via/reply {:body value
                :status 200}}))
