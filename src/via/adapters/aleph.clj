;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.adapters.aleph
  (:refer-clojure :exclude [subs send])
  (:require [via.adapter :as adapter]
            [via.http-server :as http-server]
            [buddy.sign.jwt :as jwt]
            [buddy.core.nonce :as bn]
            [tempus.core :as t]
            [tempus.duration :as td]
            [tempus.transit :as tt]
            [aleph.http :as http]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [clojure.string :as st])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(declare send* disconnect* connect* handle-request handle-connection)

(defn websocket-adapter
  [{:keys [websocket-options] :as adapter-opts}]
  (let [{:keys [max-frame-size] :as websocket-options} (merge {:max-frame-size (* 4 1024 1024)} websocket-options)
        endpoint (reify adapter/Endpoint
                   (opts [endpoint] adapter-opts)
                   (send [endpoint peer-id message]
                     (send* endpoint peer-id message))
                   (disconnect [endpoint peer-id]
                     (disconnect* endpoint peer-id))
                   (connect [endpoint address]
                     (connect* endpoint address))
                   (shutdown [endpoint]
                     ))]
    (adapter/add-event-listener endpoint :open (partial handle-connection endpoint))
    (fn ([] endpoint)
      ([request]
       (handle-request endpoint request :websocket-options websocket-options)))))

;;; Implementation

(defn- send*
  [endpoint peer-id message]
  (s/put! (get-in @(adapter/peers endpoint) [peer-id :connection]) message))

(defn- connect*
  [endpoint address]
  @(http/websocket-client address))

(defn- disconnect*
  [endpoint peer-id]
  (.close (get-in @(adapter/peers endpoint) [peer-id :connection])))

(defn- handle-request
  [endpoint request & {:keys [websocket-options]}]
  (let [peer-id (get-in request [:headers "sec-websocket-key"])]
    (d/let-flow [socket (-> request
                            (http/websocket-connection websocket-options)
                            (d/catch (fn [_] nil)))]
                (when socket
                  ((adapter/handle-connect endpoint) (constantly endpoint)
                   {:id peer-id
                    :connection socket
                    :request (assoc request :peer-id peer-id)})))))

(defn- handle-connection
  [endpoint [_ {:keys [connection request]}]]
  (let [{:keys [peer-id]} request]
    (when (not peer-id)
      (throw (ex-info "No peer-id on request"
                      {:request request})))
    (d/loop []
      (d/chain (s/take! connection ::drained)
               (fn [msg]
                 (if (identical? ::drained msg)
                   ::drained
                   (try ((adapter/handle-message endpoint)
                         (constantly endpoint)
                         (assoc request :peer-id peer-id)
                         msg)
                        (catch Exception e
                          (println "Exception occurred handling message" e)))))
               (fn [result]
                 (when-not (identical? ::drained result)
                   (d/recur)))))))
