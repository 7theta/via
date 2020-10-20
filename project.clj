;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/via "6.1.0"
  :description "A re-frame library for WebSocket based messaging"
  :url "https://github.com/7theta/via"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[re-frame "1.1.1"]
                 [haslett "0.1.6"]

                 [buddy/buddy-sign "3.2.0"]
                 [buddy/buddy-hashers "1.6.0"]
                 [com.cognitect/transit-clj "1.0.324" :exclusions [com.fasterxml.jackson.core/jackson-core]]

                 [luminus/ring-undertow-adapter "1.1.3"]
                 [ring/ring-core "1.8.2" :exclusions [ring/ring-codec]]
                 [ring/ring-defaults "0.3.2"]
                 [ring/ring-anti-forgery "1.3.0"]
                 [compojure "1.6.2"]
                 [environ "1.2.0"]

                 [com.7theta/distantia "0.2.2"]
                 [com.7theta/signum "3.0.2"]
                 [com.7theta/utilis "1.9.0"]
                 [tick "0.4.24-alpha"]
                 [integrant "0.8.0"]]
  :profiles {:dev {:source-paths ["dev" "example/src"]
                   :resource-paths ["example/resources"]
                   :clean-targets ^{:protect false} ["example/resources/public/js/compiled" "target"]
                   :env {:malli "true"}
                   :plugins [[lein-environ "0.4.0"]]
                   :dependencies [[binaryage/devtools "1.0.2"]
                                  [thheller/shadow-cljs "2.11.5"]
                                  [integrant/repl "0.3.2"]
                                  [org.clojure/clojurescript "1.10.773"]
                                  [metosin/malli "0.2.0"]]}}
  :prep-tasks ["compile"]
  :scm {:name "git"
        :url "https://github.com/7theta/via"})
