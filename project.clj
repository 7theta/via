;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/via "8.0.0"
  :description "A re-frame library for WebSocket based messaging"
  :url "https://github.com/7theta/via"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[re-frame "1.2.0"]
                 [haslett "0.1.6"]

                 [buddy/buddy-sign "3.3.0"]
                 [buddy/buddy-hashers "1.7.0"]
                 [com.cognitect/transit-clj "1.0.324" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [org.clojure/core.async "1.3.618"]

                 [aleph "0.4.7-alpha7"]

                 [ring/ring-core "1.9.3" :exclusions [ring/ring-codec]]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-anti-forgery "1.3.0"]
                 [compojure "1.6.2"]
                 [environ "1.2.0"]

                 [com.7theta/distantia "0.2.2"]
                 [com.7theta/signum "4.1.0"]
                 [com.7theta/tempus "0.3.1"]
                 [com.7theta/utilis "1.12.2"]

                 [integrant "0.8.0"]]
  :profiles {:dev {:source-paths ["dev" "example/src"]
                   :resource-paths ["example/resources"]
                   :clean-targets ^{:protect false} ["example/resources/public/js/compiled" "target"]
                   :env {:malli "true"}
                   :plugins [[lein-environ "0.4.0"]]
                   :dependencies [[binaryage/devtools "1.0.3"]
                                  [thheller/shadow-cljs "2.12.5"]
                                  [integrant/repl "0.3.2"]
                                  [org.clojure/clojurescript "1.10.844"]
                                  [metosin/malli "0.5.0"]]}}
  :prep-tasks ["compile"]
  :scm {:name "git"
        :url "https://github.com/7theta/via"})
