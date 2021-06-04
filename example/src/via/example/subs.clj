(ns via.example.subs
  (:require [via.telemetry :as telemetry]
            [signum.signal :as s]
            [signum.subs :refer [reg-sub subscribe]]
            [integrant.repl.state :refer [system]]
            [utilis.fn :refer [fsafe]]))

(reg-sub
 :api.example/auto-increment-counter
 (fn [query-v]
   (let [counter (s/signal 0)
         counter-loop (future
                        (loop []
                          (s/alter! counter inc)
                          (Thread/sleep 1000)
                          (recur)))]
     {:counter counter
      :counter-loop counter-loop}))
 (fn [{:keys [counter-loop]} _]
   (future-cancel counter-loop))
 (fn [{:keys [counter]} _]
   @counter))

(reg-sub
 :api.example.auto-increment-counter/multiply
 (fn [[_ factor]]
   (* @(subscribe [:api.example/auto-increment-counter]) factor)))

(reg-sub
 :api.example/metrics
 (fn [_]
   (let [metrics (s/signal nil)
         update-loop (future
                       (loop []
                         (s/alter! metrics #(try (when-let [endpoint (:via/endpoint system)]
                                                   (telemetry/metrics endpoint))
                                                 (catch Exception e
                                                   %)))
                         (Thread/sleep 1000)
                         (recur)))]
     {:metrics metrics
      :update-loop update-loop}))
 (fn [{:keys [update-loop]} _]
   (future-cancel update-loop))
 (fn [{:keys [metrics]} _]
   @metrics))
