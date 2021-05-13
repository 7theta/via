(ns via.util.re-frame
  (:require [re-frame.registrar :as rfr]
            [re-frame.core :as rf]))

(defn adapter
  [[id & _ :as query] ref]
  #_(rf/reg-sub
     id
     (fn [db query-v]
       (swap! subscriptions conj query-v)
       (get-in db (path query-v)))))
