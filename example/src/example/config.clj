(ns example.config
  (:require [example.events]
            [example.subs]
            [integrant.core :as ig]))

;;; Public

(def config
  {:via/authenticator
   {:query-fn (ig/ref [:example/user-store])}
   :via/endpoint
   {}
   :via/events
   {:endpoint (ig/ref :via/endpoint)}
   :via/subs
   {:endpoint (ig/ref :via/endpoint)}
   :via/http-server
   {:ring-handler (ig/ref :example/ring-handler)}

   :example/user-store
   nil

   :example/ring-handler
   {:via-handler (ig/ref :via/endpoint)}

   :example/broadcaster
   {:frequency 5
    :via-endpoint (ig/ref :via/endpoint)}})
