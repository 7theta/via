(ns via.example.views
  (:require [via.core :refer [subscribe dispatch]]
            [utilis.js :as j]
            [cljs.pprint :refer [pprint]]))

(defn dispatch-reply
  []
  (let [last-reply @(subscribe [:example/dispatch-reply])]
    [:div {:style {:padding 24
                   :min-width 300}}
     [:h2 "Remote Dispatch Example"]
     [:button {:on-click (fn [_]
                           (-> (dispatch [:api.example/echo (update last-reply :counter #(inc (or % 0)))])
                               (j/call :then #(dispatch [:example.dispatch-reply/updated (:body %)]))
                               (j/call :catch #(js/console.error (if (map? %) (clj->js %) %)))))}
      "Remote Dispatch"]
     [:div {:style {:margin-top 4}}
      "Last Reply: " (if last-reply (str last-reply) "none")]]))

(defn remote-subs
  []
  [:div {:style {:padding 24
                 :min-width 300}}
   [:h2 "Remote Sub Example"]
   [:div "Auto Incrementing Counter: " @(subscribe [:api.example/auto-increment-counter])]
   [:div "Counter Multiplied by 5: " @(subscribe [:api.example.auto-increment-counter/multiply 5])]])

(defn reconnect
  []
  [:div {:style {:padding 24
                 :min-width 300}}
   [:h2 "Reconnect"]])

(defn ring-routing
  []
  [:div {:style {:padding 24}}
   [:h2 "Ring Routing"]])

(defn percentiles
  [{:keys [value tx] :or {tx identity}}]
  [:div
   (->> value
        (sort-by first)
        (map (fn [[percentile value]]
               [:div {:key (str percentile)}
                (str percentile ": " (tx value))]))
        (doall))])

(defn historical
  [{:keys [value tx] :or {tx identity}}]
  (let [{:keys [total]} value]
    [:div
     (->> (dissoc value :total)
          (sort-by first)
          (map (fn [[ago value]]
                 [:div {:key (str ago)}
                  (str ago "m: " (tx value))]))
          (doall))
     [:div "Total: " (str total)]]))

(defn metrics
  []
  [:div {:style {:padding 24}}
   [:h2 "Metrics"]
   [:div {:style {:display "flex"
                  :flex-direction "row"
                  :justify-content "start"
                  :flex-wrap "wrap"}}
    (->> @(subscribe [:api.example/metrics])
         (sort-by (juxt :type :name))
         (map-indexed (fn [index {:keys [name value type]}]
                        [:div {:key (str name "-" index)
                               :style {:margin-right 24
                                       :margin-bottom 24
                                       :min-width 250}}
                         [:h3 {:style {:margin 0}}
                          (str name " (" (clojure.core/name type) ")")]
                         [:div {:style {:white-space "pre"}}
                          (condp = type
                            :timer [percentiles {:value value
                                                 :tx (fn [value] (str (j/call (/ value 1000000) :toFixed 2) "ms"))}]
                            :histogram [percentiles {:value value}]
                            :meter [historical {:value value
                                                :tx (fn [value] (str (j/call value :toFixed 2) "/s"))}]
                            [:div (str value)])]]))
         (doall))]]

  )

(defn main-panel []
  [:div {:style {:padding 24}}
   [:div {:style {:display "flex"
                  :flex-direction "row"
                  :justify-content "start"
                  :flex-wrap "wrap"}}
    [dispatch-reply]
    [remote-subs]
    [reconnect]]
   [ring-routing]
   [metrics]])
