;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/via "0.3.0"
  :description "A WebSocket abstraction"
  :url "https://github.com/7theta/via"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.473"]

                 [com.taoensso/sente "1.11.0"]

                 [com.cognitect/transit-clj "0.8.297"]
                 [com.cognitect/transit-cljs "0.8.239"]

                 [com.stuartsierra/component "0.3.2"]]
  :profiles {:dev {:plugins [[lein-cljsbuild "1.1.5"]
                             [lein-figwheel "0.5.9" :exclusions [cider/cider-nrepl
                                                                 org.clojure/clojure]]]
                   :dependencies [[reloaded.repl "0.2.3"]
                                  [org.clojure/tools.namespace "0.2.11"]
                                  [com.taoensso/timbre "4.8.0"]

                                  [org.clojure/core.async "0.2.395"]
                                  [ring "1.5.1"]
                                  [ring/ring-defaults "0.2.3"]

                                  [figwheel-sidecar "0.5.9"]
                                  [com.cemerick/piggieback "0.2.1"]]
                   :source-paths ["dev" "example/src"]
                   :resource-paths ["example/resources"]
                   :clean-targets ^{:protect false} ["example/resources/public/js/compiled" "target"]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src" "example/src"]
                        :figwheel {:on-jsload "via.example.client.core/start-system"
                                   :load-warninged-code true}
                        :compiler {:main via.example.client.core
                                   :output-to "example/resources/public/js/compiled/app.js"
                                   :output-dir "example/resources/public/js/compiled/out"
                                   :asset-path "js/compiled/out"
                                   :source-map-timestamp true
                                   :pretty-print true}}
                       {:id "min"
                        :source-paths ["src" "example/src"]
                        :compiler {:main via.example.client.core
                                   :output-to "example/resources/public/js/compiled/app.js"
                                   :optimizations :advanced
                                   :pretty-print false}}]}
  :scm {:name "git"
        :url "https://github.com/7theta/via"})
