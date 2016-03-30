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
  (:require [via.example.server.system :as ss]
            [via.example.server.web-handler :refer [sente-ring-handler]]

            [reloaded.repl :refer [system init start stop go reset]]
            [clojure.tools.namespace.repl :refer [refresh refresh-all]]

            [clojure.pprint :refer [pprint]]
            [clojure.reflect :refer [reflect]]
            [clojure.repl :refer [apropos dir doc find-doc pst source]]

            [figwheel-sidecar.system :as fig]

            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log]))

(defrecord FigwheelServer [client-proxy]
  component/Lifecycle
  (start [component]
    (if-not (:figwheel component)
      (assoc component
             :figwheel (component/start
                        (component/system-map
                         :figwheel-system (-> (fig/fetch-config)
                                              (assoc-in [:figwheel-options :ring-handler]
                                                        (sente-ring-handler client-proxy))
                                              fig/figwheel-system)
                         :css-watcher (fig/css-watcher
                                       {:watch-paths ["resources/public/css"]}))))
      component))
  (stop [component]
    (if-let [figwheel (:figwheel component)]
      (do (component/stop figwheel)
          (dissoc component :figwheel))
      component)))

(defn figwheel-server
  []
  (FigwheelServer. nil))

(reloaded.repl/set-init! #(assoc (ss/system nil)
                                 :web-server (component/using (figwheel-server)
                                                              [:client-proxy])))

(defn cljs-repl
  "Initializes and starts the cljs REPL"
  []
  (fig/cljs-repl (get-in system [:web-server :figwheel :figwheel-system])))
