(ns via.example.core
  (:require [via.example.app :refer [app]]
            [via.example.events]
            [via.example.subs]
            [via.example.views :as views]
            [reagent.core :as reagent]
            [re-frame.core :as re-frame]))

(defn ^:dev/after-load mount-root []
  (re-frame/clear-subscription-cache!)
  (reagent/render [views/main-panel] (.getElementById js/document "app")))

(defn ^:export init []
  (re-frame/dispatch-sync [:initialize-db])
  (enable-console-print!)
  (mount-root))
