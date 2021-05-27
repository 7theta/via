;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.telemetry
  (:require [via.subs :as subs]
            [metrics.core :as metrics]
            [metrics.gauges :as gauges]
            [metrics.counters :as counters]
            [metrics.histograms :as histograms]
            [metrics.timers :as timers]))

(defn metrics
  ([]
   (merge

    (reduce (fn [query-v->metrics query-v]
              (assoc-in query-v->metrics [:subs query-v] (metrics query-v)))
            {:subs {:registered (gauges/value (gauges/gauge ["signum" "subs" "registered"]))
                    :subscribed (gauges/value (gauges/gauge ["signum" "subs" "subscribed"]))
                    :active (gauges/value (gauges/gauge ["signum" "subs" "active"]))
                    :running (gauges/value (gauges/gauge ["signum" "subs" "running"]))}}
            (subs/subs))))
  ([query]
   (if (keyword query)
     {:count (counters/value (counters/counter ["signum.events/handler-fn" "counter" (str query)]))
      :timings (timers/percentiles (timers/timer ["signum.events/handler-fn" "timer" (str query)]))}
     {:count (counters/value (counters/counter ["signum.subs/compute-fn" "counter" (str query)]))
      :timings (timers/percentiles (timers/timer ["signum.subs/compute-fn" "timer" (str query)]))})))
