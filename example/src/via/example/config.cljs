(ns via.example.config
  (:require [via.endpoint]
            [via.subs]
            [via.defaults :as defaults]
            [via.example.test-client]
            [integrant.core :as ig]
            [via.endpoint :as via]))

;;; Public

(def config
  {:via/endpoint {:peers #{defaults/default-via-url}}
   :via/subs {:endpoint (ig/ref :via/endpoint)}

   :via.example/test-client {:endpoint (ig/ref :via/endpoint)}})
