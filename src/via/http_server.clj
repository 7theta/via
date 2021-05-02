;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.http-server
  (:require [ring.adapter.undertow :refer [run-undertow]]
            [ring.adapter.undertow.middleware.session :refer [wrap-session]]
            [utilis.fn :refer [fsafe]]
            [integrant.core :as ig]
            [clojure.set :as set]))

(defmethod ig/init-key :via/http-server [_ opts]
  (let [ring-handler (atom (delay (:ring-handler opts)))]
    {:ring-handler ring-handler
     :http-server (run-undertow (fn [req] ((fsafe @@ring-handler) req))
                                (merge
                                 {:http2? true}
                                 (-> opts
                                     (dissoc :ring-handler)
                                     (set/rename-keys {:http-port :port
                                                       :https-port :ssl-port}))))}))

(defmethod ig/halt-key! :via/http-server [_ {:keys [http-server]}]
  (when http-server (.stop http-server)))

(defmethod ig/suspend-key! :via/http-server [_ {:keys [ring-handler]}]
  (reset! ring-handler (promise)))

(defmethod ig/resume-key :via/http-server [key opts old-opts old-impl]
  (if (= (dissoc opts :ring-handler) (dissoc old-opts :ring-handler))
    (do (deliver @(:ring-handler old-impl) (:ring-handler opts))
        old-impl)
    (do (ig/halt-key! key old-impl)
        (ig/init-key key opts))))
