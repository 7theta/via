(ns example.subs
  (:require [via.subs :refer [reg-sub-via]]
            [via.authenticator :as auth]))

(reg-sub-via
 :api.example/auto-increment-count
 [#'auth/interceptor]
 (fn [{:keys [callback] :as ctx} args]
   (let [count (atom 0)]
     (future
       (loop []
         (callback (swap! count inc))
         (Thread/sleep 1000)
         (recur)))))
 (fn [context]
   (future-cancel context)))
