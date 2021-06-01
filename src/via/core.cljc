(ns via.core
  (:require [via.endpoint :as via]
            [via.subs :as vs]
            [via.events :as ve]
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
   (when (or (not endpoint) (not peer-id))
     (throw (ex-info "An endpoint and peer-id must be provided when subscribing to a sub"
                     {:endpoint (boolean endpoint)
                      :peer-id peer-id
                      :query query})))
   #?(:cljs (rf/subscribe endpoint peer-id query default)
      :clj (vs/subscribe endpoint peer-id query default))))

(defn dispatch
  "Dispatch `event` to `peer-id` through `endpoint`."
  ([event]
   (let [endpoint (first @via/endpoints)]
     (dispatch endpoint (via/first-peer endpoint) event nil)))
  ([event options]
   (let [endpoint (first @via/endpoints)]
     (dispatch endpoint (via/first-peer endpoint) event options)))
  ([endpoint peer-id event]
   (dispatch endpoint peer-id event nil))
  ([endpoint peer-id event options]
   (when (or (not endpoint) (not peer-id))
     (throw (ex-info "An endpoint and peer-id must be provided when dispatching an event"
                     {:endpoint (boolean endpoint)
                      :peer-id peer-id
                      :event event})))
   #?(:cljs (rf/dispatch endpoint peer-id event options)
      :clj (ve/dispatch endpoint peer-id event options))))
