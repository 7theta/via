(ns example.subs
  (:require [via.subs :refer [reg-sub-via]]))

(reg-sub-via
 :api.example/auto-increment-count
 (fn [sub-v callback-fn]
   (let [count (atom 0)]
     (future
       (loop []
         (callback-fn (swap! count inc))
         (Thread/sleep 5000)
         (recur)))))
 (fn [context]
   (future-cancel context)))
