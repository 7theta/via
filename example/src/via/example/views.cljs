(ns via.example.views
  (:require [via.subs :refer [subscribe]]
            [re-frame.core :refer [dispatch]]))

(defn main-panel []
  [:div {:style {:margin "40px"}}
   [:span (str "Connected: " @(subscribe [:via.endpoint/connected]))]
   [:div {:style {:margin-top "20px"}}
    "Large Message String: " (some-> @(subscribe [:api.example/large-message])
                                     (update :string count))]
   [:br]
   [:div
    [:br]
    [:div {:style {:margin-top "20px"}}
     "Count: " @(subscribe [:via.example/count])
     [:button {:on-click #(dispatch [:via.example/increment-count])
               :style {:margin-left "12px"}} [:font {:size "+1"} "+"]]]
    [:br]
    [:div {:style {:margin-top "20px"}}
     "Auto Increment Count: " (str @(subscribe [:api.example/auto-increment-count]))]
    [:div {:style {:margin-top "20px"}}
     "Auto Increment String: " (str @(subscribe [:api.example/auto-increment-string "foo"]))]]])
