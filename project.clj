;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/via "0.7.1"
  :description "A WebSocket abstraction"
  :url "https://github.com/7theta/via"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[com.taoensso/sente "1.11.0"]

                 [com.cognitect/transit-clj "0.8.300"]
                 [com.cognitect/transit-cljs "0.8.243"]

                 [integrant "0.6.2"]
                 [clojure-future-spec "1.9.0-beta4"]]
  :profiles {:dev {:plugins [[lein-cljsbuild "1.1.7"]
                             [lein-figwheel "0.5.14" :exclusions [cider/cider-nrepl
                                                                  org.clojure/clojure]]]
                   :dependencies [[org.clojure/clojure "1.9.0"]
                                  [org.clojure/clojurescript "1.9.946"]
                                  [org.clojure/core.async "0.3.465"]

                                  [org.clojure/tools.namespace "0.2.11"]

                                  [ring "1.6.3"]
                                  [ring/ring-defaults "0.3.1"]
                                  [compojure "1.6.0"]

                                  [figwheel-sidecar "0.5.14"]
                                  [com.cemerick/piggieback "0.2.2"]
                                  [integrant/repl "0.2.0"]]
                   :source-paths ["dev" "example/src"]
                   :resource-paths ["example/resources"]
                   :clean-targets ^{:protect false} ["example/resources/public/js/compiled" "target"]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src" "example/src"]
                        :figwheel {:load-warninged-code true}
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
