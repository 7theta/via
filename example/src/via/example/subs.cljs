(ns via.example.subs
  (:require [via.core :refer [subscribe]]
            [re-frame.core :refer [reg-sub]]))

(reg-sub
 :example/dispatch-reply
 (fn [db _]
   (:example/dispatch-reply db)))

(reg-sub
 :example.peers/connected
 (fn [db _]
   (:peers/connected db)))

(reg-sub
 :example/connection-status
 (fn [] (subscribe [:example.peers/connected]))
 (fn [peers _]
   (if (boolean (seq peers))
     :connected
     :disconnected)))
