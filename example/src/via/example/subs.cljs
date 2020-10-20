(ns via.example.subs
  (:require [re-frame.core :refer [reg-sub]]))

(reg-sub
 :via.example/count
 (fn [db _]
   (get db :counter 0)))
