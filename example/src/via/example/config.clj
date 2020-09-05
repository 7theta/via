(ns via.example.config
  (:require [via.example.events]
            [via.example.subs]
            [integrant.core :as ig]))

;;; Public

(def config
  {:via/authenticator
   {:endpoint (ig/ref :via/endpoint)
    :query-fn (ig/ref [:via.example/user-store])}
   :via/endpoint
   {}
   :via/events
   {:endpoint (ig/ref :via/endpoint)}
   :via/subs
   {:endpoint (ig/ref :via/endpoint)}
   :via/http-server
   {:ring-handler (ig/ref :via.example/ring-handler)}

   :via.example/user-store
   nil

   :via.example/ring-handler
   {:via-handler (ig/ref :via/endpoint)}

   :via.example/broadcaster
   {:frequency 5
    :via-endpoint (ig/ref :via/endpoint)}})
