(ns example.streams
  (:require [via.streams :refer [reg-stream-via]]
            [via.authenticator :as auth]))

(reg-stream-via
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
