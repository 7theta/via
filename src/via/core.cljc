(ns via.core
  (:require [via.endpoint :as via]
            [via.subs :as vs]
            [via.defaults :as defaults]
            [via.util.promise :as p]
            #?(:cljs [via.re-frame :as rf])
            [signum.subs :as ss]))

(defn subscribe
  ([query]
   (let [endpoint (first @via/endpoints)]
     (subscribe endpoint (via/first-peer endpoint) query nil)))
  ([query default]
   (let [endpoint (first @via/endpoints)]
     (subscribe endpoint (via/first-peer endpoint) query default)))
  ([endpoint peer-id query]
   (subscribe endpoint peer-id query nil))
  ([endpoint peer-id query default]
   #?(:cljs (rf/subscribe endpoint peer-id query default)
      :clj (vs/subscribe endpoint peer-id query default))))

(defn dispatch
  "Dispatch `event` to `peer-id` through `endpoint`."
  ([event]
   (let [endpoint (first @via/endpoints)]
     (dispatch endpoint (via/first-peer endpoint) event nil)))
  ([endpoint event]
   (dispatch endpoint (via/first-peer endpoint) event nil))
  ([endpoint peer-id event]
   (dispatch endpoint peer-id event nil))
  ([endpoint peer-id event {:keys [timeout] :or {timeout defaults/request-timeout}}]
   (let [{:keys [on-success on-failure on-timeout promise]} (p/adapter)]
     (via/send endpoint peer-id event
               :on-success on-success
               :on-failure on-failure
               :on-timeout on-timeout
               :timeout timeout)
     promise)))
