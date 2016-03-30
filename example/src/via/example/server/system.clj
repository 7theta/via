;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.example.server.system
  (:require [via.example.server.handlers :refer [msg-handler]]
            [via.server
             [client-proxy :refer [client-proxy]]
             [router :refer [router]]]
            [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
            [com.stuartsierra.component :as component]))

;;; Public

(defn system
  [config_]
  (component/system-map
   :client-proxy (client-proxy sente-web-server-adapter)
   :router (component/using (router msg-handler)
                            [:client-proxy])))
