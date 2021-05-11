;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.http-server
  (:require [aleph.http :as http]
            [utilis.fn :refer [fsafe]]
            [integrant.core :as ig]
            [clojure.set :as set])
  (:import [java.net InetAddress]))

(defmethod ig/init-key :via/http-server
  [_ opts]
  (let [ring-handler (atom (delay (:ring-handler opts)))
        {:keys [http-host http-port]} opts]
    {:ring-handler ring-handler
     :http-server (http/start-server
                   (fn [request] ((fsafe @@ring-handler) request))
                   (cond-> opts
                     true (dissoc :ring-handler :http-port)

                     (and (not http-host) http-port)
                     (assoc :port http-port)

                     (and http-host http-port)
                     (assoc :socket-address (InetAddress/getByName (str http-host ":" http-port)))))}))

(defmethod ig/halt-key! :via/http-server
  [_ {:keys [http-server]}]
  (when http-server (.close http-server)))

(defmethod ig/suspend-key! :via/http-server
  [_ {:keys [ring-handler]}]
  (reset! ring-handler (promise)))

(defmethod ig/resume-key :via/http-server
  [key opts old-opts old-impl]
  (if (= (dissoc opts :ring-handler) (dissoc old-opts :ring-handler))
    (do (deliver @(:ring-handler old-impl) (:ring-handler opts))
        old-impl)
    (do (ig/halt-key! key old-impl)
        (ig/init-key key opts))))
