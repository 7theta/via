;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.example.client.config
  (:require [via.example.client.handlers :refer [msg-handler]]
            [via.client.server-proxy]
            [via.client.router]
            [integrant.core :as ig]))

;;; Public

(def config
  {:via.client/server-proxy
   nil

   :via.client/router
   {:msg-handler msg-handler
    :server-proxy (ig/ref :via.client/server-proxy)}})
