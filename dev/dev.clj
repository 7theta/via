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
  (:require [example.config :refer [config]]

            [integrant.core :as ig]

            [integrant.repl :refer [clear go halt init reset reset-all]]
            [integrant.repl.state :refer [system]]

            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]

            [clojure.tools.namespace.repl :refer [refresh refresh-all disable-reload!]]
            [clojure.repl :refer [apropos dir doc find-doc pst source]]
            [clojure.test :refer [run-tests run-all-tests]]
            [clojure.pprint :refer [pprint]]
            [clojure.reflect :refer [reflect]]))

(disable-reload! (find-ns 'integrant.core))

(def dev-config
  (-> config
      (assoc-in [:via/http-server :port] 3449)
      (assoc :shadow-cljs {})))

(defmethod ig/init-key :shadow-cljs [_ _]
  (server/start!)
  (shadow/watch :app))

(defmethod ig/halt-key! :shadow-cljs [_ _]
  (server/stop!))

(ig/load-namespaces dev-config)

(integrant.repl/set-prep! (constantly dev-config))
