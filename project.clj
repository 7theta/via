;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(defproject com.7theta/via "3.2.0"
  :description "A re-frame library for WebSocket based messaging"
  :url "https://github.com/7theta/via"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.520"]

                 [reagent "0.8.1"]
                 [re-frame "0.10.6"]
                 [haslett "0.1.6"]

                 [buddy/buddy-auth "2.1.0" :exclusions [clout]]
                 [buddy/buddy-hashers "1.3.0"]
                 [com.cognitect/transit-clj "0.8.313" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [http-kit "2.4.0-alpha3"]

                 [com.7theta/signum "2.0.0"]
                 [com.7theta/distantia "0.2.1"]

                 [com.7theta/utilis "1.3.0"]
                 [integrant "0.7.0"]]
  :profiles {:dev {:source-paths ["dev" "example/src"]
                   :resource-paths ["example/resources"]
                   :clean-targets ^{:protect false} ["example/resources/public/js/compiled" "target"]
                   :dependencies [[ring/ring-core "1.7.1" :exclusions [ring/ring-codec]]
                                  [ring/ring-defaults "0.3.2"]
                                  [ring/ring-anti-forgery "1.3.0"]
                                  [compojure "1.6.1"]

                                  [com.google.javascript/closure-compiler-unshaded "v20190528"]
                                  [org.clojure/google-closure-library "0.0-20190213-2033d5d9"]
                                  [binaryage/devtools "0.9.10"]
                                  [thheller/shadow-cljs "2.8.39"]
                                  [integrant/repl "0.3.1"]]}}
  :prep-tasks ["compile"]
  :scm {:name "git"
        :url "https://github.com/7theta/via"})
