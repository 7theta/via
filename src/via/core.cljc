(ns via.core
  (:require [via.endpoint :as via]
            [via.adapter :as adapter]
            #?(:clj [manifold.deferred :as d])
            #?(:cljs [cljs.core.async :refer [go <! chan put! close!]])))

(declare promise-adapter)

(defn subscribe
  []

  )

(defn dispatch
  "Dispatch `event` to `peer-id` through `endpoint`."
  ([event]
   (let [endpoint (first @via/endpoints)]
     (dispatch endpoint (via/first-peer endpoint) event nil)))
  ([endpoint event]
   (dispatch endpoint (via/first-peer endpoint) event nil))
  ([endpoint peer-id event]
   (dispatch endpoint peer-id event nil))
  ([endpoint peer-id event {:keys [timeout] :as opts}]
   (let [{:keys [on-success on-failure on-timeout promise]} (promise-adapter)]
     (via/send endpoint peer-id event
               :on-success on-success
               :on-failure on-failure
               :on-timeout on-timeout
               :timeout (or timeout 10000))
     promise)))

;;; Implementation

(defn- promise-adapter
  []
  #?(:clj (let [p (d/deferred)]
            {:promise p
             :on-success (fn [reply] (d/success! p reply))
             :on-failure (fn [reply] (d/error! p reply))
             :on-timeout (fn [] (d/error! p {:error ::timeout}))})
     :cljs (let [ch (chan)
                 p (js/Promise.
                    (fn [resolve reject]
                      (go (try (when-let [{:keys [f v]} (<! ch)]
                                 (condp = f
                                   :resolve (resolve v)
                                   :reject (reject v)))
                               (catch js/Error e
                                 (reject e)))
                          (close! ch))))]
             {:promise p
              :on-success (fn [reply] (put! ch {:f :resolve :v reply}))
              :on-failure (fn [reply] (put! ch {:f :reject :v reply}))
              :on-timeout (fn [] (put! ch {:f :reject :v {:error ::timeout}}))})))
