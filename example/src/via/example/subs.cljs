(ns via.example.subs
  (:require [re-frame.core :refer [reg-sub]]))

(reg-sub
 :example/dispatch-reply
 (fn [db _]
   (:example/dispatch-reply db)))
