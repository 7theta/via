(ns via.example.config
  (:require [via.example.events]
            [via.example.subs]
            [integrant.core :as ig]))

;;; Public

(def config
  {:via/endpoint {:exports {:namespaces #{:via.example/subs
                                          :via.example/events}}}
   :via/subs {:endpoint (ig/ref :via/endpoint)}
   :via/http-server {:ring-handler (ig/ref :via.example/ring-handler)}
   :via.example/ring-handler {:via-handler (ig/ref :via/endpoint)}
   :via.example/broadcaster {:frequency 5
                             :via-endpoint (ig/ref :via/endpoint)}})
