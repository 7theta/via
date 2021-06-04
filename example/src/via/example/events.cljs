(ns via.example.events
  (:require [via.example.db :as db]
            [signum.events :as se]
            [re-frame.core :refer [reg-event-db reg-event-fx]]
            [via.endpoint :as via]))

(reg-event-db
 :initialize-db
 (fn [_ _]
   db/default-db))

(reg-event-fx
 :example.dispatch-reply/updated
 (fn [{:keys [db]} [_ value]]
   {:db (assoc db :example/dispatch-reply value)}))

(reg-event-fx
 :example.peer/connected
 (fn [{:keys [db]} [_ peer-id]]
   {:db (update db :peers/connected #(conj (set %) peer-id))}))

(reg-event-fx
 :example.peer/disconnected
 (fn [{:keys [db]} [_ peer-id]]
   {:db (update db :peers/connected #(disj (set %) peer-id))}))
