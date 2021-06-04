(ns via.example.config
  (:require [via.endpoint]
            [via.subs]
            [via.defaults :as defaults]
            [via.example.test-client]
            [via.core :refer [dispatch]]
            [integrant.core :as ig]
            [via.endpoint :as via]))

;;; Public

(def config
  {:via/endpoint {:peers #{defaults/default-via-url}
                  :event-listeners {:via.endpoint.peer/connected (fn [[_ {:keys [id] :as peer}]]
                                                                   (dispatch [:example.peer/connected id]))
                                    :via.endpoint.peer/disconnected (fn [[_ {:keys [id] :as peer}]]
                                                                      (dispatch [:example.peer/disconnected id]))}}
   :via/subs {:endpoint (ig/ref :via/endpoint)}
   :via.example/test-client {:endpoint (ig/ref :via/endpoint)}})
