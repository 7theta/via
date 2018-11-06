;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/via "1.3.0"
  :description "A re-frame library for WebSocket based messaging"
  :url "https://github.com/7theta/via"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.339"]
                 [org.clojure/core.async "0.4.474"]

                 [reagent "0.8.1"]
                 [re-frame "0.10.6"]
                 [haslett "0.1.2" :exclusions [org.clojure/core.async]]

                 [buddy/buddy-auth "2.1.0" :exclusions [clout]]
                 [buddy/buddy-hashers "1.3.0"]
                 [com.cognitect/transit-clj "0.8.313" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [http-kit "2.3.0"]

                 [com.7theta/signum "0.3.0"]
                 [com.7theta/distantia "0.1.0"]

                 [com.7theta/utilis "1.1.0"]
                 [integrant "0.6.3"]]
  :source-paths ["src/clj" "src/cljs" "src/cljc"]
  :profiles {:dev {:source-paths ["dev/clj" "example/src/clj"]
                   :resource-paths ["example/resources"]
                   :clean-targets ^{:protect false} ["example/resources/public/js/compiled" "target"]
                   :dependencies [[ring/ring-core "1.7.0" :exclusions [ring/ring-codec]]
                                  [ring/ring-defaults "0.3.2"]
                                  [ring/ring-anti-forgery "1.3.0"]
                                  [compojure "1.6.1"]

                                  [binaryage/devtools "0.9.10"]
                                  [figwheel-sidecar "0.5.16"]
                                  [com.cemerick/piggieback "0.2.2"]
                                  [org.clojure/tools.nrepl "0.2.13"]
                                  [integrant/repl "0.3.1"]
                                  [day8.re-frame/re-frame-10x "0.3.3-react16"]]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :plugins [[lein-cljsbuild "1.1.7" :exclusions [org.apache.commons/commons-compress]]
                             [lein-figwheel "0.5.14" :exclusions [org.clojure/clojure]]]}}
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src/cljs" "src/cljc" "example/src/cljs"]
                        :figwheel {:on-jsload "example.core/mount-root"}
                        :compiler {:main example.core
                                   :output-to "example/resources/public/js/compiled/app.js"
                                   :output-dir "example/resources/public/js/compiled/out"
                                   :asset-path "js/compiled/out"
                                   :source-map-timestamp true
                                   :preloads [devtools.preload
                                              day8.re-frame-10x.preload]
                                   :closure-defines {"re_frame.trace.trace_enabled_QMARK_" true}
                                   :external-config {:devtools/config {:features-to-install :all}}}}]}
  :prep-tasks ["compile"]
  :scm {:name "git"
        :url "https://github.com/7theta/via"})
