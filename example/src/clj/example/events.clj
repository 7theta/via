(ns example.events
  (:require [via.events :refer [reg-event-via]]
            [via.authenticator :as auth]))

(defonce counter (atom 0))

(reg-event-via
 :api.example/increment-count
 [#'auth/interceptor]
 (fn [_ _]
   {:reply (swap! counter inc)}))
