(ns example.subs
  (:require [via.endpoint :as via]
            [via.authenticator :as auth]
            [signum.subs :refer [reg-sub subscribe make-signal]]))

(reg-sub
 :api.example/my-counter
 (fn [query-v]
   (let [count (atom 0)
         counter-loop (future
                        (loop []
                          (swap! count inc)
                          (Thread/sleep 1000)
                          (recur)))]
     (make-signal (fn [] count) :on-dispose #(future-cancel counter-loop))))
 (fn [value query-v]
   value))

(reg-sub
 :api.example/auto-increment-count
 [#'via/interceptor #'auth/interceptor]
 (fn [query-v]
   (subscribe [:api.example/my-counter]))
 (fn [value query-v]
   [value (inc value)]))

(reg-sub
 :api.example/auto-increment-string
 [#'via/interceptor #'auth/interceptor]
 (fn [query-v]
   [(subscribe [:api.example/my-counter])])
 (fn [[value] [_ some-text]]
   [value (str some-text "-" value)]))
