;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application."
  (:require [via.example.server.app :as app]
            [via.example.server.web-handler :refer [sente-ring-handler]]

            [clojure.tools.namespace.repl :refer [refresh refresh-all]]

            [clojure.pprint :refer [pprint]]
            [clojure.reflect :refer [reflect]]
            [clojure.repl :refer [apropos dir doc find-doc pst source]]

            [figwheel-sidecar.system :as fig]

            [integrant.core :as ig]
            [com.stuartsierra.component :as component])) ; Figwheel dependency

(def config (assoc app/config :figwheel {:client-proxy
                                         (ig/ref :via.server.client-proxy/client-proxy)}))

(defmethod ig/init-key :figwheel [_ {:keys [client-proxy]}]
  (component/start
   (component/system-map
    :figwheel-system (-> (fig/fetch-config)
                         (assoc-in [:data :figwheel-options :ring-handler]
                                   (sente-ring-handler client-proxy))
                         fig/figwheel-system)
    :css-watcher (fig/css-watcher
                  {:watch-paths ["resources/public/css"]}))))

(defmethod ig/halt-key! :figwheel [_ figwheel-system]
  (component/stop figwheel-system))

(ig/load-namespaces config)

(def app nil)

(defn start []
  (alter-var-root #'app (constantly (ig/init config))))

(defn stop []
  (alter-var-root #'app (constantly (ig/halt! app))))

(defn cljs-repl
  "Initializes and starts the cljs REPL"
  []
  (fig/cljs-repl (get-in app [:web-server :figwheel :figwheel-system])))
