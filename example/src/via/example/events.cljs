(ns via.example.events
  (:require [via.example.db :as db]
            [via.events :refer [reg-event-via]]
            [via.fx :as via-fx]
            [re-frame.core :refer [reg-event-db reg-event-fx]]
            [via.endpoint :as via]))

(reg-event-db
 :initialize-db
 (fn [_ _]
   db/default-db))

(reg-event-fx
 :via.example/increment-count
 (fn [_ _]
   {:via/dispatch {:event [:api.example/increment-count]
                   :on-success [:via.example.increment-count/succeeded]
                   :on-failure [:via.example.increment-count/failed]
                   :on-timeout [:via.example.increment-count/timed-out]}}))

(reg-event-fx
 :via.example.increment-count/succeeded
 (fn [{:keys [db]} [_ count]]
   (js/console.log ":via.example.increment-count/succeeded" count)
   {:db (assoc db :counter count)}))

(reg-event-fx
 :via.example.increment-count/failed
 (fn [{:keys [db]} [_ error]]
   (js/console.log ":via.example.increment-count/failed" error)))

(reg-event-fx
 :via.example.increment-count/timed-out
 (fn [_ error]
   (js/console.error ":via.example.increment-count/timed-out" (pr-str error))))

(reg-event-via
 :via.example/server-broadcast
 (fn [_ [_ message]]
   (js/console.log "Server Push:" (clj->js message))))
