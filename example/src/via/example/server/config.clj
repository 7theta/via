;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.example.server.config
  (:require [via.example.server.handlers :refer [msg-handler]]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
            [integrant.core :as ig]))

;;; Public

(def config {:via.server.client-proxy/client-proxy
             {:sente-web-server-adapter (get-sch-adapter)}

             :via.server.router/router
             {:msg-handler msg-handler
              :client-proxy (ig/ref :via.server.client-proxy/client-proxy)}

             :via.example.server.broadcaster/broadcaster
             {:frequency 5
              :client-proxy (ig/ref :via.server.client-proxy/client-proxy)}})
