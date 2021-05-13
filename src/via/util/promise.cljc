(ns via.util.promise
  (:require #?(:clj [manifold.deferred :as d])
            #?(:cljs [cljs.core.async :refer [go <! chan put! close!]])))

(defn adapter
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
