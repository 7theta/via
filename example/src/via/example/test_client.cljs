(ns via.example.test-client
  (:require [via.endpoint :as ve]
            [re-frame.core :refer [dispatch]]
            [integrant.core :as ig]))

(defmethod ig/init-key :via.example/test-client
  [_ {:keys [endpoint]}]
  (let [conn-key (ve/add-event-listener endpoint :via.endpoint/connected #(js/console.log "WebSocket Connected" (pr-str %)))
        disc-key (ve/add-event-listener endpoint :via.endpoint/disconnected #(js/console.log "WebSocket Disconnected" (pr-str %)))]
    {:shutdown #(do (ve/remove-event-listener endpoint :via.endpoint/connected conn-key)
                    (ve/remove-event-listener endpoint :via.endpoint/disconnected disc-key))}))

(defmethod ig/halt-key! :via.example/test-client
  [_ {:keys [shutdown]}]
  (shutdown))
