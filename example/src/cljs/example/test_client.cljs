(ns example.test-client
  (:require [via.endpoint :refer [subscribe unsubscribe] :as via]
            [re-frame.core :refer [dispatch]]
            [integrant.core :as ig]))

(defmethod ig/init-key :example/test-client
  [_ {:keys [endpoint]}]
  {:endpoint endpoint
   :sub-key (subscribe endpoint
                       {:open #(js/console.log "WebSocket Connected" %)
                        :close #(do (js/console.log "WebSocket Disconnected" %)
                                    (dispatch [:example/logout]))})})

(defmethod ig/halt-key! :example/test-client
  [_ {:keys [endpoint sub-key]}]
  (unsubscribe endpoint sub-key))
