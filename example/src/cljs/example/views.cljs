(ns example.views
  (:require [re-frame.core :refer [subscribe dispatch]]))

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
                 :style {:margin-left "12px"}} [:font {:size "+1"} "+"]]]]
     [:button {:on-click #(dispatch [:example/login])} [:font {:size "+1"} "Login"]])])
