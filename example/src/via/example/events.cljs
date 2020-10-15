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
 :via.example/login
 (fn [{:keys [db]} _]
   {:via/dispatch
    {:event [:via/id-password-login {:id "admin" :password "admin"}]
     :on-success [:via.example.login/succeeded]
     :on-failure [:via.example.login/failed]
     :on-timeout [:via.example.login/timed-out]}}))

(reg-event-fx
 :via.example.login/succeeded
 (fn [{:keys [db]} [_ login-creds]]
   {:db (assoc db :authenticated login-creds)}))

(reg-event-db
 :via.example.login/failed
 (fn [db error]
   (js/console.error ":via.example.login/failed" (pr-str error))
   (dissoc db :authenticated)))

(reg-event-db
 :via.example.login/timed-out
 (fn [db error]
   (js/console.error ":via.example.login/timed-out" (pr-str error))
   (dissoc db :authenticated)))

(reg-event-fx
 :via.example/logout
 (fn [{:keys [db]} _]
   {:db (dissoc db :authenticated)
    :via/dispatch {:event [:via/logout]}}))

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
