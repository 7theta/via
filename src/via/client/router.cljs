;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.client.router
  (:require [taoensso.sente :refer [start-client-chsk-router!]]
            [integrant.core :as ig]))

;;; Public

(declare router)

(defmethod ig/init-key ::router [_ {:keys [server-proxy msg-handler] :as opts}]
  (router server-proxy msg-handler))

(defmethod ig/halt-key! ::router [_ router]
  (when router (router)))

(defn router
  [server-proxy msg-handler]
  (start-client-chsk-router! (:recv-ch server-proxy) msg-handler))
