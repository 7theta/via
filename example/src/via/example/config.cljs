(ns via.example.config
  (:require [via.endpoint]
            [via.events]
            [via.subs]
            [via.fx]
            [via.example.test-client]
            [integrant.core :as ig]
            [via.endpoint :as via]))

;;; Public

(def config
  {:via/endpoint
   {}

   :via/events
   {:endpoint (ig/ref :via/endpoint)}

   :via/subs
   {:endpoint (ig/ref :via/endpoint)}

   :via/fx
   {:endpoint (ig/ref :via/endpoint)}

   :via.example/test-client
   {:endpoint (ig/ref :via/endpoint)}})
