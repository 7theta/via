;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.client.server-proxy
  (:require [via.defaults :refer [default-sente-endpoint]]
            [taoensso.sente :refer [make-channel-socket-client! cb-success?]]
            [taoensso.sente.packers.transit :refer [get-transit-packer]]
            [integrant.core :as ig]))

;;; Public

(declare server-proxy)

(defmethod ig/init-key ::server-proxy [_ opts]
  (server-proxy opts))

(defn server-proxy
  [{:keys [sente-endpoint]
    :or {sente-endpoint default-sente-endpoint}
    :as opts}]
  (let [packer (get-transit-packer :json)
        {:keys [ch-recv send-fn state]}
        (make-channel-socket-client! sente-endpoint
                                     (merge {:type :auto :packer packer} opts))]
    {:recv-ch ch-recv
     :send-fn send-fn
     :state state}))

(defn send!
  "Asynchronously sends 'message' to the server encapsulated by
  'server-proxy'. An optional 'callback' can be provided if a
  reply is expected from the server, however a 'timeout' must
  also be provided with it."
  [server-proxy message & {:keys [callback timeout]}]
  {:pre [(if-not (nil? callback) (number? timeout) (nil? timeout))]}
  (if callback
    ((:send-fn server-proxy) message timeout callback)
    ((:send-fn server-proxy) message)))

(defn success?
  "Returns a truthy value indicating if 'reply' was a success.
  Useful for testing the reply in callbacks."
  [reply]
  (cb-success? reply))
