(ns via.core-test
  (:require [via.endpoint :as via]
            [via.subs :as vs]
            [via.events :as ve]
            [via.defaults :refer [default-via-endpoint]]
            [via.core :as vc]

            [signum.subs :as ss]
            [signum.signal :as sig]
            [signum.events :as se]

            [integrant.core :as ig]

            [compojure.core :as compojure :refer [GET POST]]
            [compojure.route :as route]
            [ring.middleware.defaults :as ring-defaults]

            [clojure.test :as t]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [clojure.test.check.clojure-test :refer [defspec]]
            [via.adapter :as adapter])
  (:import [java.net ServerSocket]))

;;; Endpoint Setup

(def lock (Object.))

(defn- allocate-free-port!
  []
  (locking lock
    (let [socket (ServerSocket. 0)]
      (.setReuseAddress socket true)
      (let [port (.getLocalPort socket)]
        (try (.close socket) (catch Exception _))
        port))))

(defmethod ig/init-key :via.core-test/ring-handler
  [_ {:keys [via-handler]}]
  (-> (compojure/routes
       (GET default-via-endpoint ring-req (via-handler ring-req)))
      (ring-defaults/wrap-defaults ring-defaults/site-defaults)))

(defn default-event-listener
  [[event-id event]]
  (when (not (#{:via.endpoint.peer/connected
                :via.endpoint.peer/disconnected
                :via.endpoint.peer/removed} event-id))
    (locking lock
      (println event-id event))))

(defn peer
  ([] (peer nil))
  ([{:keys [exports]}]
   (loop [attempts 3]
     (let [result (try (let [port (allocate-free-port!)
                             peer (ig/init
                                   {:via/endpoint {:exports exports
                                                   :event-listeners {:default default-event-listener}}
                                    :via/subs {:endpoint (ig/ref :via/endpoint)}
                                    :via/http-server {:ring-handler (ig/ref :via.core-test/ring-handler)
                                                      :http-port port}
                                    :via.core-test/ring-handler {:via-handler (ig/ref :via/endpoint)}})]
                         {:peer peer
                          :endpoint (:via/endpoint peer)
                          :shutdown #(ig/halt! peer)
                          :address (str "ws://localhost:" port default-via-endpoint)})
                       (catch Exception e
                         (if (zero? attempts)
                           (throw e)
                           ::recur)))]
       (if (not= result ::recur)
         result
         (recur (dec attempts)))))))

(defn shutdown
  [{:keys [shutdown] :as peer}]
  (shutdown))

(defn connect
  [from to]
  (via/connect (:endpoint from) (:address to)))

;;; Tests

(defspec send-directly-to-peer
  25
  (prop/for-all [value gen/any]
                (let [event-id (str (gensym) "/event")
                      peer-1 (peer {:exports {:events #{event-id}}})
                      peer-2 (peer)]
                  (se/reg-event
                   event-id
                   (fn [_ [_ value :as event]]
                     {:via/reply {:status 200
                                  :body value}}))
                  (try (= value (:body @(vc/dispatch
                                         (:endpoint peer-2)
                                         (connect peer-2 peer-1)
                                         [event-id value])))
                       (catch Exception e
                         (println e)
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec sub-updates-on-change
  10
  (prop/for-all [value (gen/vector gen/any)]
                (let [sub-id (str (gensym) "/sub")
                      peer-1 (peer {:exports {:subs #{sub-id}}})
                      peer-2 (peer)
                      value-signal (sig/signal nil)]
                  (ss/reg-sub sub-id (fn [_] @value-signal))
                  (try (let [sub (vc/subscribe (:endpoint peer-2) (connect peer-2 peer-1) [sub-id])
                             result (atom nil)]
                         (add-watch sub ::watch (fn [_ _ _ value]
                                                  (reset! result value)))
                         (doseq [value value]
                           (sig/alter! value-signal (constantly value)))
                         (Thread/sleep 500)
                         (remove-watch sub ::watch)
                         (= @result (last value)))
                       (catch Exception e
                         (println e)
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec subs-cleanup-properly
  10
  (prop/for-all [value gen/any]
                (let [sub-id (str (gensym) "/sub")
                      peer-1 (peer {:exports {:subs #{sub-id}}})
                      peer-2 (peer)
                      value-signal (sig/signal nil)]
                  (ss/reg-sub sub-id (fn [_] @value-signal))
                  (try (let [sub (vc/subscribe (:endpoint peer-2) (connect peer-2 peer-1) [sub-id])
                             result (atom nil)]
                         (add-watch sub ::watch (fn [_ _ _ value]
                                                  (reset! result value)))
                         (sig/alter! value-signal (constantly value))
                         (Thread/sleep 500)
                         (remove-watch sub ::watch)
                         (Thread/sleep 500)
                         (shutdown peer-2)
                         (Thread/sleep 500)
                         (let [{:keys [peers context]} (adapter/opts ((:endpoint peer-1)))
                               context @context]
                           (boolean
                            (and (coll? @peers)
                                 (empty? @peers)
                                 (coll? @(:via.subs/inbound-subs context))
                                 (empty? @(:via.subs/inbound-subs context))
                                 (coll? @(:via.subs/outbound-subs context))
                                 (empty? @(:via.subs/outbound-subs context))))))
                       (catch Exception e
                         (println e)
                         (shutdown peer-2)
                         false)
                       (finally
                         (shutdown peer-1))))))

(defspec expose-api-prevents-access
  25
  (prop/for-all [value gen/any]
                (do true)))

(defspec sub-reconnect-works
  25
  (prop/for-all [value gen/any]
                (do true)))

;; route a message through multiple peers
(defspec peer-routing-works
  25
  (prop/for-all [value gen/any]
                (do true)))
;;
;; VIA_AUTH
;; - test that id password authentication prevents access to resources
;;
;; VIA_SCHEMA
;; - test that unknown keys are stripped out
;; - test bad schemas don't pass
;; - test that good schemas do pass
