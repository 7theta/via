;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application."
  (:require [via.example.config :refer [config]]
            [via.core :as via]

            [integrant.core :as ig]

            [integrant.repl :refer [clear go halt init reset reset-all]]
            [integrant.repl.state :refer [system]]

            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]

            [clojure.tools.namespace.repl :refer [refresh refresh-all disable-reload!]]
            [clojure.repl :refer [apropos dir doc find-doc pst source]]
            [clojure.test :refer [run-tests run-all-tests]]
            [clojure.pprint :refer [pprint]]
            [clojure.reflect :refer [reflect]]
            [signum.events :as se]))

(disable-reload! (find-ns 'integrant.core))

(def dev-config
  (-> config
      (assoc-in [:via/http-server :http-port] 3449)
      #_(assoc :shadow-cljs {})))

(defmethod ig/init-key :shadow-cljs [_ _]
  (server/start!)
  (shadow/watch :app))

(defmethod ig/halt-key! :shadow-cljs [_ _]
  (server/stop!))

(ig/load-namespaces dev-config)

(integrant.repl/set-prep! (constantly dev-config))

(defn default-event-listener
  [prefix [event-id event]]
  (condp = event-id
    :open (println prefix event-id (:id event))
    :close (println prefix event-id (:id event))
    (println prefix event-id event)))

(defonce peer-1 (atom nil))
(defn start-peer-1
  []
  (when-let [system @peer-1] (ig/halt! system))
  (reset! peer-1
          (ig/init {:via/endpoint {:events #{:foo.bar/baz}
                                   :event-listeners {:default (partial default-event-listener :peer-1)}}
                    :via/events {:endpoint (ig/ref :via/endpoint)}
                    :via/subs {:endpoint (ig/ref :via/endpoint)}
                    :via/http-server {:ring-handler (ig/ref :via.example/ring-handler)
                                      :http-port 5000}
                    :via.example/ring-handler {:via-handler (ig/ref :via/endpoint)}})))

(defonce peer-2 (atom nil))
(defn start-peer-2
  []
  (when-let [system @peer-2] (ig/halt! system))
  (reset! peer-2
          (ig/init {:via/endpoint {:peers #{"ws://localhost:5000/via"}
                                   :event-listeners {:default (partial default-event-listener :peer-2)}}
                    :via/events {:endpoint (ig/ref :via/endpoint)}
                    :via/subs {:endpoint (ig/ref :via/endpoint)}
                    :via/http-server {:ring-handler (ig/ref :via.example/ring-handler)
                                      :http-port 5001}
                    :via.example/ring-handler {:via-handler (ig/ref :via/endpoint)}})))

(se/reg-event
 :foo.bar/baz
 (fn [context event]
   {:via/reply {:status 200
                :body {:handled event}}}))

(comment

  (do (start-peer-1) (start-peer-2))

  @(via/dispatch (:via/endpoint @peer-2) [:foo.bar/baz])


  )
