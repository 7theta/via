(ns example.test-client
  (:require [via.endpoint :refer [subscribe dispose] :as via]
            [re-frame.core :refer [dispatch]]
            [integrant.core :as ig]))

(defmethod ig/init-key :example/test-client
  [_ {:keys [endpoint]}]
  {:endpoint endpoint
   :sub-key (subscribe endpoint
                       {:open #(js/console.log "WebSocket Connected" (pr-str %))
                        :close #(do (js/console.log "WebSocket Disconnected" (pr-str %))
                                    (dispatch [:example/logout]))})})

(defmethod ig/halt-key! :example/test-client
  [_ {:keys [endpoint sub-key]}]
  (dispose endpoint sub-key))
