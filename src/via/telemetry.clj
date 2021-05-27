;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.telemetry
  (:require [via.adapter :as adapter]
            [metrics.core :as metrics]
            [metrics.gauges :as gauges]
            [metrics.counters :as counters]
            [metrics.histograms :as histograms]
            [metrics.timers :as timers]
            [metrics.meters :as meters]))

(defn metrics
  [endpoint]
  (let [metrics (cond
                  (:metrics (map? endpoint)) endpoint
                  (fn? endpoint) (adapter/opt (endpoint) :metrics)
                  :else (throw (ex-info "Unable to interpret endpoint metrics" {:endpoint endpoint})))
        {:keys [keys static dynamic]} metrics]
    (map (fn [[key [metric-group metric-type metric-name :as metric-title]]]
           {:key key
            :group metric-group
            :type (keyword metric-type)
            :name metric-name
            :title metric-title
            :value (condp = (keyword metric-type)
                     :counter (counters/value (counters/counter metric-title))
                     :meter (meters/rates (meters/meter metric-title))
                     :histogram (histograms/percentiles (histograms/histogram metric-title))
                     :timer (timers/percentiles (timers/timer metric-title))
                     (throw (ex-info "Unrecognized metric type"
                                     {:key key
                                      :metric-title metric-title})))})
         @keys)))
