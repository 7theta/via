;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.http-server
  (:require [org.httpkit.server :as http]
            [integrant.core :as ig]))

(defmethod ig/init-key :via/http-server [_ opts]
  (let [ring-handler (atom (delay (:ring-handler opts)))]
    {:ring-handler ring-handler
     :stop-server (http/run-server (fn [req] (@@ring-handler req)) (dissoc opts :ring-handler))}))

(defmethod ig/halt-key! :via/http-server [_ {:keys [stop-server]}]
  (stop-server))

(defmethod ig/suspend-key! :via/http-server [_ {:keys [ring-handler]}]
  (reset! ring-handler (promise)))

(defmethod ig/resume-key :via/http-server [key opts old-opts old-impl]
  (if (= (dissoc opts :ring-handler) (dissoc old-opts :ring-handler))
    (do (deliver @(:ring-handler old-impl) (:ring-handler opts))
        old-impl)
    (do (ig/halt-key! key old-impl)
        (ig/init-key key opts))))
