(ns via.events
  (:require [via.endpoint :as via]
            [via.defaults :as defaults]
            [via.util.promise :as p]))

(declare chain-handlers)

(defn dispatch
  [endpoint peer-id event {:keys [timeout]
                           :or {timeout defaults/request-timeout}
                           :as options}]
  (when (or (not endpoint) (not peer-id))
    (throw (ex-info "An endpoint and peer-id must be provided when dispatching an event"
                    {:endpoint (boolean endpoint)
                     :peer-id peer-id
                     :event event})))
  (let [{:keys [promise] :as adapter} (p/adapter)
        chain-handlers (chain-handlers adapter options)]
    (via/send endpoint peer-id event
              :on-success (chain-handlers :on-success)
              :on-failure (chain-handlers :on-failure)
              :on-timeout (chain-handlers :on-timeout)
              :timeout timeout)
    promise))

;;; Implementation

(defn- chain-handlers
  [adapter options]
  (fn [key]
    (if-let [f (get options key)]
      (comp f (get adapter key))
      (get adapter key))))
