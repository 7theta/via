(ns example.subs
  (:require-macros [reagent.ratom :refer [reaction]])
  (:require [via.subs :refer [reg-sub-via]]
            [via.streams :refer [reg-acc-via]]
            [re-frame.core :refer [reg-sub subscribe]]))

(reg-sub
 :example/authenticated?
 (fn [db _]
   (get-in db [:authenticated :token])))

(reg-sub
 :example/count
 (fn [db _]
   (get db :counter 0)))

(reg-sub-via
 :api.example/auto-increment-count
 (fn [value]
   value))

(reg-acc-via
 :api.example.acc/auto-increment-count
 (fn [values]
   values))

(reg-sub
 :api.example.acc/auto-increment-count-sum
 (fn [] (subscribe [:api.example.acc/auto-increment-count]))
 (fn [values] (apply + values)))
