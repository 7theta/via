(ns example.subs
  (:require [via.endpoint :as via]
            [via.authenticator :as auth]
            [signum.atom :as s]
            [signum.subs :refer [reg-sub subscribe track-signal]]))

(reg-sub
 :api.example/my-counter
 (fn [query-v]
   (let [counter (s/atom 0)
         counter-loop (future
                        (loop []
                          (swap! counter inc)
                          (Thread/sleep 1000)
                          (recur)))]
     (track-signal counter :on-dispose (fn []
                                         (future-cancel counter-loop)))))
 (fn [counter query-v]
   @counter))

(reg-sub
 :api.example/auto-increment-count
 [#'via/interceptor #'auth/interceptor]
 (fn [query-v]
   (let [value @(subscribe [:api.example/my-counter])]
     [value (inc value)])))

(reg-sub
 :api.example/auto-increment-string
 [#'via/interceptor #'auth/interceptor]
 (fn [[_ some-text]]
   (let [value @(subscribe [:api.example/my-counter])]
     {:value value
      :str (str some-text "-" value)})))
