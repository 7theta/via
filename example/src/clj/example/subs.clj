(ns example.subs
  (:require [via.endpoint :as via]
            [via.authenticator :as auth]
            [signum.subs :refer [reg-sub]]))

(reg-sub
 :api.example/auto-increment-count
 [#'via/interceptor #'auth/interceptor]
 (fn [query-v]
   [(let [count (atom 0)]
      (future
        (loop []
          (swap! count inc)
          (Thread/sleep 1000)
          (recur)))
      count)])
 identity)
