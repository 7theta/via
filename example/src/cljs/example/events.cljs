(ns example.events
  (:require [example.app :refer [reset-app!]]
            [example.db :as db]
            [via.events :refer [reg-event-via]]
            [via.fx :as via-fx]
            [re-frame.core :refer [reg-event-db reg-event-fx]]
            [via.endpoint :as via]))

(reg-event-db
 :initialize-db
 (fn [_ _]
   db/default-db))

(reg-event-fx
 :example/login
 (fn [{:keys [db]} _]
   {:via/dispatch
    {:event [:via/id-password-login {:id "admin" :password "admin"}]
     :on-success [:example.login/succeeded]
     :on-failure [:example.login/failed]
     :on-timeout [:example.login/timed-out]}}))

(reg-event-fx
 :example.login/succeeded
 (fn [{:keys [db]} [_ login-creds]]
   {:db (assoc db :authenticated login-creds)}))

(reg-event-db
 :example.login/failed
 (fn [db error]
   (js/console.error ":example.login/failed" (pr-str error))
   (dissoc db :authenticated)))

(reg-event-db
 :example.login/timed-out
 (fn [db error]
   (js/console.error ":example.login/timed-out" (pr-str error))
   (dissoc db :authenticated)))

(reg-event-fx
 :example/logout
 (fn [_ _]
   (reset-app!)
   {:dispatch [:initialize-db]}))

(reg-event-fx
 :example/increment-count
 (fn [_ _]
   {:via/dispatch {:event [:api.example/increment-count]
                   :on-success [:example.increment-count/succeeded]
                   :on-failure [:example.increment-count/failed]
                   :on-timeout [:example.increment-count/timed-out]}}))

(reg-event-fx
 :example.increment-count/succeeded
 (fn [{:keys [db]} [_ count]]
   (js/console.log ":example.increment-count/succeeded" count)
   {:db (assoc db :counter count)}))

(reg-event-fx
 :example.increment-count/failed
 (fn [{:keys [db]} [_ error]]
   (js/console.log ":example.increment-count/failed" error)))

(reg-event-fx
 :example.increment-count/timed-out
 (fn [_ error]
   (js/console.error ":example.increment-count/timed-out" (pr-str error))))

(reg-event-via
 :example/server-broadcast
 (fn [_ [_ message]]
   (js/console.log "Server Push:" message)))
