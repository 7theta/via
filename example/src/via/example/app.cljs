(ns via.example.app
  (:require [via.example.config :refer [config]]
            [integrant.core :as ig]))

(defonce app (ig/init config))
