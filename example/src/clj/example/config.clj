(ns example.config
  (:require [example.events]
            [integrant.core :as ig]))

;;; Public

(def config
  {:via/authenticator
   {:query-fn (ig/ref [:example/user-store])}

   :via/endpoint
   {:authenticator (ig/ref :via/authenticator)}

   :via/events
   {:endpoint (ig/ref :via/endpoint)}

   :example/user-store
   nil

   :example/ring-handler
   {:via-handler (ig/ref :via/endpoint)}

   :example/broadcaster
   {:frequency 5
    :via-endpoint (ig/ref :via/endpoint)}})
