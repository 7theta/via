(ns example.subs
  (:require [via.endpoint :as via]
            [via.authenticator :as auth]
            [signum.subs :refer [reg-sub subscribe]]))

(reg-sub
 :api.example/my-counter
 (fn [query-v]
   (let [count (atom 0)]
     (future
       (loop []
         (swap! count inc)
         (Thread/sleep 1000)
         (recur)))
     [count]))
 (fn [[value] query-v]
   value))

(reg-sub
 :api.example/auto-increment-count
 [#'via/interceptor #'auth/interceptor]
 (fn [query-v]
   [(subscribe [:api.example/my-counter])])
 (fn [[value] query-v]
   [value (inc value)]))
