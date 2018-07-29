(ns example.views
  (:require [via.subs :as via]
            [re-frame.core :refer [subscribe dispatch]]))

(defn main-panel []
  [:div {:style {:margin "40px"}}
   [:br]
   (if @(subscribe [:example/authenticated?])
     [:div
      [:button {:on-click #(dispatch [:example/logout])} [:font {:size "+1"} "Logout"]]
      [:br]
      [:div {:style {:margin-top "20px"}}
       "Count: " @(subscribe [:example/count])
       [:button {:on-click #(dispatch [:example/increment-count])
                 :style {:margin-left "12px"}} [:font {:size "+1"} "+"]]]
      [:br]
      [:div {:style {:margin-top "20px"}}
       "Auto Increment Count: " (str @(via/subscribe [:api.example/auto-increment-count]))]
      [:div {:style {:margin-top "20px"}}
       "Auto Increment String: " (str @(via/subscribe [:api.example/auto-increment-string "foo"]))]]
     [:button {:on-click #(dispatch [:example/login])} [:font {:size "+1"} "Login"]])])
