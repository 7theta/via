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
            [via.endpoint :as ve]

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

            [signum.events :as se]
            [signum.subs :as ss]
            [via.subs :as vs]
            [signum.signal :as sig]))

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
    :via.endpoint.peer/connect (println prefix event-id (:id event))
    :via.endpoint.peer/disconnect (println prefix event-id (:id event))
    :via.endpoint.peer/remove (println prefix event-id (:id event))
    (println prefix event-id event)))

(defonce peer-1 (atom nil))
(defn stop-peer-1
  []
  (when-let [system @peer-1]
    (ig/halt! system)))
(defn start-peer-1
  []
  (stop-peer-1)
  (reset! peer-1
          (ig/init {:via/endpoint {:events #{:foo.bar/baz}
                                   :subs #{:foo.bar/sub}
                                   :event-listeners {:default (partial default-event-listener :peer-1)}}
                    :via/subs {:endpoint (ig/ref :via/endpoint)}
                    :via/http-server {:ring-handler (ig/ref :via.example/ring-handler)
                                      :http-port 5000}
                    :via.example/ring-handler {:via-handler (ig/ref :via/endpoint)}})))

(defonce peer-2 (atom nil))
(defn stop-peer-2
  []
  (when-let [system @peer-2]
    (ig/halt! system)))
(defn start-peer-2
  []
  (stop-peer-2)
  (reset! peer-2
          (ig/init {:via/endpoint {:peers #{"ws://localhost:5000/via"}
                                   :heartbeat-interval 5000
                                   :event-listeners {:default (partial default-event-listener :peer-2)}}
                    :via/subs {:endpoint (ig/ref :via/endpoint)}
                    :via/http-server {:ring-handler (ig/ref :via.example/ring-handler)
                                      :http-port 5001}
                    :via.example/ring-handler {:via-handler (ig/ref :via/endpoint)}})))

(se/reg-event
 :foo.bar/baz
 (fn [context event]
   {:via/reply {:status 200
                :body {:handled event}}}))

(def counter (sig/signal 0))

(ss/reg-sub
 :foo.bar/sub
 (fn [query]
   {:sub/response {:query query
                   :counter @counter}}))

(defn test-basic
  []

  (do (start-peer-1)
      (start-peer-2)
      (let [endpoint (:via/endpoint @peer-2)
            peer-id (ve/first-peer endpoint)]
        (ve/update-session-context endpoint peer-id (constantly {:foo :bar}))
        (println :via/dispatch-success-test @(via/dispatch endpoint peer-id [:foo.bar/baz]))
        (println :via/dispatch-failure-test (try @(via/dispatch endpoint peer-id [:foo.bar/baz2])
                                                 (catch Exception e (ex-data e))))
        (let [sub (vs/subscribe endpoint peer-id [:foo.bar/sub])]
          (add-watch sub ::sub
                     (fn [_ _ _ value]
                       (println :via/got-subscribe-value value)))))
      )

  )

(defn test-partition
  []

  (do (start-peer-1)
      (start-peer-2)
      (let [endpoint (:via/endpoint @peer-2)
            peer-id (ve/first-peer endpoint)]
        (println :ready)
        (Thread/sleep 1000)
        (println :partitioning)
        (stop-peer-1)
        (Thread/sleep 1000)
        (start-peer-1)

        )
      )

  )

(comment

  (sig/alter! counter inc)

  (test-basic)

  (test-partition)


  (stop-peer-1)
  (start-peer-1)

  )
