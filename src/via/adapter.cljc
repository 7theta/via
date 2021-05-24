;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.adapter
  (:refer-clojure :exclude [subs send])
  (:require [via.util.id :refer [uuid]])
  #?(:clj (:import [java.io Closeable])))

(defprotocol Endpoint
  (opts [endpoint])
  (send [endpoint peer-id message])
  (disconnect [endpoint peer-id reconnect])
  (connect [endpoint address])
  (shutdown [endpoint]))

(defn opt
  [endpoint key]
  (get (opts endpoint) key))

(defn peers
  [endpoint]
  (opt endpoint :peers))

(defn exports
  [endpoint]
  (opt endpoint :exports))

(defn context
  [endpoint]
  (opt endpoint :context))

(defn encode
  [endpoint]
  (opt endpoint :encode))

(defn decode
  [endpoint]
  (opt endpoint :decode))

(defn event-listeners
  [endpoint]
  (opt endpoint :event-listeners))

(defn handle-message
  [endpoint]
  (opt endpoint :handle-message))

(defn handle-connect
  [endpoint]
  (opt endpoint :handle-connect))

(defn handle-disconnect
  [endpoint]
  (opt endpoint :handle-disconnect))

(defn requests
  [endpoint]
  (opt endpoint :requests))

(defn add-event-listener
  [endpoint key listener]
  (let [listener-id (uuid)]
    (swap! (event-listeners endpoint) update key assoc listener-id listener)
    listener-id))

(defn remove-event-listener
  [endpoint key listener-id]
  (swap! (event-listeners endpoint) update key dissoc listener-id))
