;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.server.router
  (:require [taoensso.sente :refer [start-server-chsk-router!]]
            [integrant.core :as ig]))

;;; Public

(declare router)

(defmethod ig/init-key :via.server/router [_ {:keys [client-proxy msg-handler]}]
  (router client-proxy msg-handler))

(defmethod ig/halt-key! :via.server/router [_ router]
  (when router (router)))

(defn router
  [client-proxy msg-handler]
  (start-server-chsk-router! (:recv-ch client-proxy) msg-handler))
