;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.adapters.haslett
  (:refer-clojure :exclude [subs])
  (:require [via.adapter :as adapter]
            [cljs.core.async :as async]
            [haslett.client :as ws]
            [haslett.format :as fmt]))

(declare connect* disconnect* send* handle-connection)

(defn websocket-adapter
  [{:keys [encode decode] :as adapter-opts}]
  (let [endpoint (reify adapter/Endpoint
                   (opts [endpoint] adapter-opts)
                   (send [endpoint peer-id message]
                     (send* endpoint peer-id message))
                   (disconnect [endpoint peer-id reconnect]
                     (disconnect* endpoint peer-id reconnect))
                   (connect [endpoint address]
                     (connect* endpoint address))
                   (shutdown [endpoint]
                     ))]
    (adapter/add-event-listener endpoint :via.endpoint.peer/connect (partial handle-connection endpoint))
    (swap! (adapter/context endpoint) merge
           {::format (reify fmt/Format
                       (read  [_ s] s)
                       (write [_ v] v))})
    (fn ([] endpoint)
      ([request] (throw (ex-info "Unable to handle incoming requests" {:request request}))))))

;;; Implementation

(defn- send*
  [endpoint peer-id message]
  (if message
    (if-let [sink (get-in @(adapter/peers endpoint) [peer-id :connection :sink])]
      (async/put! sink message)
      (js/console.warn "No connection for peer"
                       #js {:peer-id peer-id
                            :message message}))
    (js/console.warn "Tried to put nil message on channel" #js {:peer-id peer-id})))

(defn- connect*
  [endpoint address]
  (js/Promise.
   (fn [resolve reject]
     (async/go
       (try
         (let [return (ws/connect address {:format (::format @(adapter/context endpoint))})
               {:keys [socket source sink close-status] :as stream} (async/<! return)]
           (if (ws/connected? stream)
             (resolve stream)
             (reject)))
         (catch js/Error e
           (js/console.error "Error occurred in via.adapters.haslett/connect*" e)))))))

(defn- disconnect*
  [endpoint peer-id reconnect]
  (swap! (adapter/peers endpoint) assoc-in [peer-id :reconnect] reconnect)
  (ws/close (get-in @(adapter/peers endpoint) [peer-id :connection])))

(defn- handle-connection
  [endpoint [_ {:keys [connection request]}]]
  (let [{:keys [peer-id]} request
        {:keys [close-status source]} connection]
    (when (not peer-id)
      (throw (ex-info "No peer-id on request"
                      {:request request})))
    (swap! (adapter/peers endpoint) update peer-id dissoc :reconnect)
    (async/go
      (try (loop []
             (async/alt!
               source ([message]
                       (do ((adapter/handle-message endpoint) (constantly endpoint) (assoc request :peer-id peer-id) message)
                           (recur)))
               close-status ([status]
                             (do ((adapter/handle-disconnect endpoint) (constantly endpoint)
                                  (get @(adapter/peers endpoint) peer-id))))))
           (catch js/Error e
             (js/console.error "Error occurred in via.adapters.haslett/handle-connection" e))))))
