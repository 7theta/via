(ns example.config
  (:require [via.endpoint]
            [via.events]
            [via.subs]
            [via.streams]
            [via.fx]
            [example.test-client]
            [integrant.core :as ig]
            [via.endpoint :as via]))

;;; Public

(def config
  {:via/endpoint
   {}

   :via/events
   {:endpoint (ig/ref :via/endpoint)}

   :via/streams
   {:endpoint (ig/ref :via/endpoint)
    :events (ig/ref :via/events)}

   :via/fx
   {:endpoint (ig/ref :via/endpoint)}

   :example/test-client
   {:endpoint (ig/ref :via/endpoint)}})
