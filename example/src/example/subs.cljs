(ns example.subs
  (:require [re-frame.core :refer [reg-sub]]))

(reg-sub
 :example/authenticated?
 (fn [db _]
   (get-in db [:authenticated :token])))

(reg-sub
 :example/count
 (fn [db _]
   (get db :counter 0)))
