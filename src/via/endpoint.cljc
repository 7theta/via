;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.endpoint
  (:refer-clojure :exclude [send])
  (:require #?(:clj [via.adapters.aleph :as aleph])
            #?(:cljs [via.adapters.haslett :as haslett])
            [via.adapter :as adapter]
            [via.util.id :refer [uuid]]
            [signum.fx :as sfx]
            [signum.events :as se]
            [tempus.core :as t]
            [tempus.duration :as td]
            [tempus.transit :as tt]
            [cognitect.transit :as transit]
            [utilis.fn :refer [fsafe]]
            [utilis.map :refer [map-vals]]
            [utilis.timer :as timer]
            [integrant.core :as ig]
            [clojure.set :refer [union difference]]
            [clojure.string :as st])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defonce endpoints (atom #{}))

(declare encode-message decode-message
         connect disconnect
         handle-message handle-connect handle-event
         id-seq->map)

(defmethod ig/init-key :via/endpoint
  [_ {:keys [transit-handlers adapter adapter-options peers events subs event-listeners]
      :or {adapter #?(:clj aleph/websocket-adapter
                      :cljs haslett/websocket-adapter)}}]
  (let [endpoint (adapter (merge
                           {:event-listeners (atom (map-vals (fn [handler]
                                                               {(uuid) handler})
                                                             event-listeners))
                            :subs (atom (id-seq->map subs))
                            :events (atom (id-seq->map events))
                            :peers (atom {})
                            :requests (atom {})
                            :context (atom {})
                            :handle-message (fn [& args] (apply handle-message args))
                            :handle-connect (fn [& args] (apply handle-connect args))
                            :decode (partial decode-message {:handlers (merge (:read tt/handlers) (:read transit-handlers))})
                            :encode (partial encode-message {:handlers (merge (:write tt/handlers) (:write transit-handlers))})}
                           adapter-options))]
    (swap! endpoints conj endpoint)
    (doseq [peer-address peers]
      (connect endpoint peer-address))
    endpoint))

(defmethod ig/halt-key! :via/endpoint
  [_ endpoint]
  (doseq [[peer-id _] @(adapter/peers (endpoint))]
    (disconnect endpoint peer-id))
  (adapter/shutdown (endpoint))
  (swap! endpoints disj endpoint)
  endpoint)

(defn send
  "Asynchronously sends `message` to the client for `peer-id`"
  [endpoint peer-id message & {:keys [type timeout params
                                      on-success
                                      on-failure
                                      on-timeout timeout]
                               :or {type :event
                                    timeout 30000}}]
  (let [message (merge {:type type :payload message} params)]
    (if (or on-success on-failure)
      (let [request-id (uuid)
            message (merge message
                           {:request-id request-id}
                           (when timeout {:timeout timeout}))]
        (swap! (adapter/requests (endpoint)) assoc request-id
               {:on-success on-success
                :on-failure on-failure
                :on-timeout on-timeout
                :message message
                :timer (timer/run-after
                        #(do (swap! (adapter/requests (endpoint)) dissoc request-id)
                             (try ((fsafe on-timeout))
                                  #?(:clj (catch Exception e
                                            (println "Unhandled exception in timeout handler" e))
                                     :cljs (catch js/Error e
                                             (js/console.error "Unhandled exception in timeout handler" e)))))
                        timeout)
                :timeout timeout
                :timestamp (t/now)
                :peer-id peer-id})
        (adapter/send (endpoint) peer-id ((adapter/encode (endpoint)) message)))
      (adapter/send (endpoint) peer-id ((adapter/encode (endpoint)) message)))))

(defn broadcast
  "Asynchronously sends `message` to all connected clients"
  [endpoint message]
  (doseq [[peer-id _] (adapter/peers (endpoint))]
    (send endpoint message peer-id)))

(defn disconnect
  [endpoint peer-id]
  (let [peer (get @(adapter/peers (endpoint)) peer-id)]
    (handle-event endpoint :close peer)
    (adapter/disconnect (endpoint) peer-id)
    (swap! (adapter/peers (endpoint)) dissoc peer-id)))

(defn add-event-listener
  [endpoint key listener]
  (adapter/add-event-listener (endpoint) key listener))

(defn remove-event-listener
  [endpoint key listener-id]
  (adapter/remove-event-listener (endpoint) key listener-id))

(defn handle-event
  [endpoint key event]
  (doseq [[_ handler] (concat (get @(adapter/event-listeners (endpoint)) key)
                              (get @(adapter/event-listeners (endpoint)) :default))]
    (handler [key event])))

(defn update-session-context
  [endpoint peer-id f & args]
  (swap! (adapter/event-listeners (endpoint)) update-in [peer-id :session-context] #(apply f % args)))

(defn merge-context
  [endpoint context]
  (swap! (adapter/context (endpoint)) merge context))

(defn reg-sub
  ([endpoint sub-id]
   (reg-sub endpoint sub-id {}))
  ([endpoint sub-id opts]
   (swap! (adapter/subs (endpoint)) assoc sub-id opts)))

(defn sub?
  [endpoint sub-id]
  (contains? @(adapter/subs (endpoint)) sub-id))

(defn reg-event
  ([endpoint event-id]
   (reg-event endpoint event-id {}))
  ([endpoint event-id opts]
   (swap! (adapter/events (endpoint)) assoc event-id opts)))

(defn event?
  [endpoint event-id]
  (contains? @(adapter/events (endpoint)) event-id))

(defn connect
  [endpoint peer-address]
  (let [connection (adapter/connect (endpoint) peer-address)
        peer-id (uuid)]
    ((adapter/handle-connect (endpoint)) endpoint {:id peer-id
                                                   :connection connection
                                                   :request {:peer-id peer-id
                                                             :peer-address peer-address}})
    peer-id))

(defn first-peer
  [endpoint]
  (ffirst @(adapter/peers (endpoint))))

;;; Effect Handlers

(sfx/reg-fx
 :via/disconnect
 (fn [{:keys [endpoint]} {:keys [peer-id]}]
   (disconnect (constantly endpoint) peer-id)))

(sfx/reg-fx
 :via.tags/add
 (fn [{:keys [endpoint request]} {:keys [tags]}]
   (swap! (adapter/peers (endpoint)) update-in [(:peer-id request) :tags] #(union (set %) (set tags)))))

(sfx/reg-fx
 :via.tags/remove
 (fn [{:keys [endpoint request]} {:keys [tags]}]
   (swap! (adapter/peers (endpoint)) update-in [(:peer-id request) :tags] #(difference (set %) (set tags)))))

(sfx/reg-fx
 :via.tags/replace
 (fn [{:keys [endpoint request]} {:keys [tags]}]
   (swap! (adapter/peers (endpoint)) assoc-in [(:peer-id request) :tags] (set tags))))

(sfx/reg-fx
 :via/reply
 (fn [{:keys [endpoint request]} {:keys [status body]}]
   (when (not status)
     (throw (ex-info "A status must be provided in a :via/reply"
                     {:eg {:status 200}})))
   (send endpoint (:peer-id request) body
         :type :reply
         :params {:status status
                  :request-id (:request-id request)})))

(sfx/reg-fx
 :via.session-context/replace
 (fn [{:keys [endpoint request]} session-context]
   (update-session-context endpoint (:peer-id request) (constantly session-context))))

(sfx/reg-fx
 :via.session-context/merge
 (fn [{:keys [endpoint request]} session-context]
   (update-session-context endpoint (:peer-id request) merge session-context)))

;;; Implementation

(defn- encode-message
  [handlers ^String data]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out :json handlers) data)
    (.toString out)))

(defn- decode-message
  [handlers ^String data]
  (let [in (ByteArrayInputStream. (.getBytes data))]
    (transit/read (transit/reader in :json handlers))))

(defn- handle-remote-event
  [endpoint request {:keys [payload] :as message}]
  (let [[event-id & _ :as event] payload]
    (cond
      (not (event? endpoint event-id))
      (handle-event endpoint :unknown-remote-event {:message message})

      (not (se/event? event-id))
      (handle-event endpoint :unknown-signum-event {:message message})

      :else (se/dispatch {:endpoint endpoint :request request} event))))

(defn- handle-reply
  [endpoint reply]
  (if-let [request (get @(adapter/requests (endpoint)) (:request-id reply))]
    (do ((fsafe timer/cancel) (:timer request))
        (let [f (get {200 (:on-success request)} (:status reply) (:on-failure request))]
          (try ((fsafe f) (select-keys reply [:status :payload]))
               #?(:clj (catch Exception e
                         (println "Unhandled exception in reply handler" e))
                  :cljs (catch js/Error e
                          (js/console.error "Unhandled exception in reply handler" e))))
          (swap! (adapter/requests (endpoint)) dissoc (:request-id reply))))
    (handle-event endpoint :unhandled-reply {:reply reply})))

(defn- handle-message
  [endpoint request message]
  (let [message ((adapter/decode (endpoint)) message)
        request (cond-> request
                  (:request-id message) (assoc :request-id (:request-id message)))]
    (condp = (:type message)
      :event (handle-remote-event endpoint request message)
      :reply (handle-reply endpoint message)
      (handle-event endpoint :unhandled-message {:message message}))))

(defn- handle-connect
  [endpoint peer]
  (swap! (adapter/peers (endpoint)) assoc (:id peer) peer)
  (handle-event endpoint :open peer))

(defn- id-seq->map
  [ids]
  (->> ids
       (map (fn [id]
              [id {}]))
       (into {})))
