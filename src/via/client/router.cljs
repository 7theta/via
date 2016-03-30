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
            [com.stuartsierra.component :as component]))

;;; Types

(defrecord Router [server-proxy msg-handler]
  component/Lifecycle
  (start [component]
    (if-not (:stop-fn component)
      (assoc component
             :stop-fn (start-client-chsk-router! (:recv-ch server-proxy)
                                                 msg-handler))
      component))
  (stop [component]
    (when-let [stop-fn (:stop-fn component)] (stop-fn))
    (dissoc component :stop-fn)))

;;; Public

(defn router
  [msg-handler]
  (Router. nil msg-handler))
