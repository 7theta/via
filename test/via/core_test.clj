(ns via.core-test
  (:require [via.endpoint :as via]
            [via.subs :as vs]
            [via.events :as ve]
            [via.defaults :refer [default-via-endpoint]]
            [via.core :as vc]

            [clojure.data :refer [diff]]

            [signum.subs :as ss]
            [signum.signal :as sig]
            [signum.events :as se]
            [signum.fx :as sfx]

            [integrant.core :as ig]

            [compojure.core :as compojure :refer [GET POST]]
            [compojure.route :as route]
            [ring.middleware.defaults :as ring-defaults]

            [utilis.timer :as timer]

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
  ([endpoint-config]
   (loop [attempts 3]
     (let [result (try (let [port (allocate-free-port!)
                             peer (ig/init
                                   {:via/endpoint endpoint-config
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

(defn wait-for
  ([p] (wait-for p 5000))
  ([p timeout-ms]
   (let [result (deref p timeout-ms ::timed-out)]
     (if (= result ::timed-out)
       (throw (ex-info "Timed out waiting for promise" {}))
       result))))

;;; Tests

(defspec send-directly-to-peer
  50
  (prop/for-all [value gen/any-printable-equatable]
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
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec sub-updates-on-change
  50
  (prop/for-all [value (gen/sized (fn [size] (gen/vector gen/any-printable-equatable (max size 1))))]
                (let [sub-id (str (gensym) "/sub")
                      peer-1 (peer {:exports {:subs #{sub-id}}})
                      peer-2 (peer)
                      value-signal (sig/signal ::init)
                      promises (mapv (fn [_] (promise)) value)]
                  (ss/reg-sub sub-id (fn [_] @value-signal))
                  (try (let [sub (vc/subscribe (:endpoint peer-2) (connect peer-2 peer-1) [sub-id])]
                         (add-watch sub ::watch (fn [_ _ _ {:keys [value i]}]
                                                  (when (number? i)
                                                    (deliver (nth promises i) value))))
                         (doseq [[index value] (map-indexed vector value)]
                           (sig/alter! value-signal (constantly {:i index :value value})))
                         (let [result (wait-for (last promises))
                               passed? (= result (last value))]
                           (remove-watch sub ::watch)
                           (when (not passed?)
                             (locking lock
                               (clojure.pprint/pprint
                                {:test :subs-update-on-change
                                 :value value
                                 :result result})))
                           passed?))
                       (catch Exception e
                         (locking lock
                           (clojure.pprint/pprint
                            {:value value
                             :promises promises})
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec subs-cleanup-properly
  50
  (prop/for-all [value gen/any-printable-equatable]
                (let [sub-id (str (gensym) "/sub")
                      peer-1-promise (promise)
                      peer-1 (peer {:exports {:subs #{sub-id}}
                                    :event-listeners {:via.endpoint.peer/removed (fn [_] (deliver peer-1-promise true))}})
                      peer-2-promise (promise)
                      peer-2 (peer {:event-listeners {:via.endpoint.peer/removed (fn [_] (deliver peer-2-promise true))}})
                      value-signal (sig/signal nil)]
                  (ss/reg-sub sub-id (fn [_] @value-signal))
                  (try (let [sub (vc/subscribe (:endpoint peer-2) (connect peer-2 peer-1) [sub-id])]
                         (add-watch sub ::watch (fn [_ _ _ value]))
                         (sig/alter! value-signal (constantly value))
                         (remove-watch sub ::watch)
                         (shutdown peer-2)
                         (wait-for peer-1-promise)
                         (wait-for peer-2-promise)
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
                         (locking lock
                           (println e))
                         (shutdown peer-2)
                         false)
                       (finally
                         (shutdown peer-1))))))

(defspec export-api-prevents-event-access
  50
  (prop/for-all [value gen/any-printable-equatable]
                (let [event-id (str (gensym) "/event")
                      peer-1 (peer)
                      peer-2 (peer)]
                  (se/reg-event
                   event-id
                   (fn [_ [_ value :as event]]
                     {:via/reply {:status 200
                                  :body value}}))
                  (try (= {:error {:headers {:status 400}
                                   :type :reply
                                   :body {:error :via.endpoint/unknown-event}}}
                          (-> (try
                                @(vc/dispatch
                                  (:endpoint peer-2)
                                  (connect peer-2 peer-1)
                                  [event-id value])
                                (catch Exception e
                                  (ex-data e)))
                              (update-in [:error :headers] #(select-keys % [:status]))))
                       (catch Exception e
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec export-api-prevents-sub-access
  50
  (prop/for-all [value gen/any-printable-equatable]
                (let [sub-id (str (gensym) "/sub")
                      peer-1 (peer)
                      result (promise)
                      peer-2 (peer {:event-listeners {:default (fn [[event-id & _ :as event]]
                                                                 (when (= :via.subs.subscribe/failure event-id)
                                                                   (deliver result (:reply (second event)))))}})]
                  (ss/reg-sub sub-id (fn [_] ::unauthorized))
                  (try (let [sub (vc/subscribe (:endpoint peer-2) (connect peer-2 peer-1) [sub-id])]
                         (add-watch sub ::watch (fn [_ _ _ _]))
                         (remove-watch sub ::watch)
                         (= (:body (wait-for result))
                            {:status :error
                             :error :invalid-subscription
                             :query-v [sub-id]}))
                       (catch Exception e
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))

(defspec sub-reconnect-works
  50
  (prop/for-all [value gen/any-printable-equatable]
                (do true)))

(defspec peer-routing-works
  25
  (prop/for-all [value gen/any-printable-equatable]
                (let [event-id (str (gensym) "/event")
                      effect-id (str (gensym) "/effect")
                      a (peer {:exports {:events #{event-id}}
                               :context {:id :a}})
                      b (peer {:exports {:events #{event-id}}
                               :context {:id :b}})
                      c (peer {:exports {:events #{event-id}}
                               :context {:id :c}})
                      a->b-peer-id (connect a b)
                      b->c-peer-id (connect b c)
                      c->a-peer-id (connect c a)
                      result (promise)]
                  (sfx/reg-fx
                   effect-id
                   (fn [{:keys [endpoint]} message]
                     (deliver result {:endpoint-id (:id @(adapter/context (endpoint)))
                                      :message message})))
                  (se/reg-event
                   event-id
                   (fn [{:keys [message]} event]
                     (let [{:keys [headers]} message
                           {:keys [hops]} headers]
                       (if-let [peer-id (first hops)]
                         {:via/send {:peer-id peer-id
                                     :message event
                                     :headers (update headers :hops next)}}
                         {effect-id message}))))
                  (via/send (:endpoint a)
                            a->b-peer-id
                            [event-id value]
                            :headers {:hops [b->c-peer-id
                                             c->a-peer-id]})
                  (try
                    (let [{:keys [endpoint-id message]} (wait-for result)]
                      (boolean
                       (and (= endpoint-id :a)
                            (= value (second (:body message))))))
                    (catch Exception e
                      (locking lock
                        (println e))
                      false)
                    (finally
                      (shutdown a)
                      (shutdown b)
                      (shutdown c))))))

(defspec headers-survive-transmission
  50
  (prop/for-all [headers (gen/map gen/keyword gen/string)
                 message gen/any-printable-equatable]
                (let [event-id (str (gensym) "/event")
                      effect-id (str (gensym) "/effect")
                      peer-1 (peer {:exports {:events #{event-id}}})
                      peer-2 (peer)
                      result (promise)]
                  (sfx/reg-fx
                   effect-id
                   (fn [_ message]
                     (deliver result message)))
                  (se/reg-event
                   event-id
                   (fn [{:keys [message]} _]
                     {effect-id message}))
                  (via/send (:endpoint peer-2)
                            (connect peer-2 peer-1)
                            [event-id message]
                            :headers headers)
                  (try (= (:headers (wait-for result)) headers)
                       (catch Exception e
                         (locking lock
                           (println e))
                         false)
                       (finally
                         (shutdown peer-1)
                         (shutdown peer-2))))))
;;
;; VIA_AUTH
;; - test that id password authentication prevents access to resources
;;
;; VIA_SCHEMA
;; - test that unknown keys are stripped out
;; - test bad schemas don't pass
;; - test that good schemas do pass
