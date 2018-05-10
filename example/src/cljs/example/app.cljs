(ns example.app
  (:require [example.config :refer [config]]
            [integrant.core :as ig]))

(defonce app (ig/init config))

(defn reset-app!
  []
  (ig/halt! app)
  (set! app (ig/init config)))
