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
            [via.defaults :as defaults]
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
         handle-connect handle-disconnect
         handle-message handle-event
         id-seq->map)

(defmethod ig/init-key :via/endpoint
  [_ {:keys [peers
             events
             subs
             transit-handlers
             event-listeners
             adapter
             adapter-options
             heartbeat-interval
             heartbeat-enabled]
      :or {adapter #?(:clj aleph/websocket-adapter
                      :cljs haslett/websocket-adapter)
           heartbeat-interval defaults/heartbeat-interval
           heartbeat-enabled true}}]
  (let [events (set (concat events
                            [:via.session-context/replace
                             :via.session-context/merge
                             :via.endpoint/heartbeat]))
        subs (set subs)
        endpoint (adapter (merge
                           {:event-listeners (atom (map-vals (fn [handler]
                                                               {(uuid) handler})
                                                             event-listeners))
                            :subs (atom (id-seq->map subs))
                            :events (atom (id-seq->map events))
                            :peers (atom {})
                            :requests (atom {})
                            :context (atom {})
                            :handle-message (fn [& args] (apply handle-message args))
                            :handle-disconnect (fn [& args] (apply handle-disconnect args))
                            :handle-connect (fn [& args] (apply handle-connect args))
                            :decode (partial decode-message {:handlers (merge (:read tt/handlers) (:read transit-handlers))})
                            :encode (partial encode-message {:handlers (merge (:write tt/handlers) (:write transit-handlers))})
                            :heartbeat-interval heartbeat-interval
                            :heartbeat-enabled heartbeat-enabled}
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
  (let [message (merge (when type {:type type})
                       (when message {:payload message})
                       params)]
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
  (adapter/disconnect (endpoint) peer-id))

(defn first-peer
  [endpoint]
  (ffirst @(adapter/peers (endpoint))))

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

(defn session-context
  ([] (session-context (first @endpoints)))
  ([endpoint] (session-context endpoint (first-peer endpoint)))
  ([endpoint peer-id]
   (-> @(adapter/peers (endpoint))
       (get peer-id)
       :session-context)))

(defn update-session-context
  ([endpoint peer-id f]
   (update-session-context endpoint peer-id true f))
  ([endpoint peer-id sync f]
   (let [peers (adapter/peers (endpoint))]
     (when (contains? @peers peer-id)
       (let [session-context (-> peers
                                 (swap! update-in [peer-id :session-context] f)
                                 (get peer-id)
                                 :session-context)]
         (handle-event endpoint :via.endpoint.session-context/updated session-context)
         (when sync
           (let [event-handler-args {:peer-id peer-id :session-context session-context}]
             (send endpoint peer-id [:via.session-context/replace {:session-context session-context :sync false}]
                   :on-success #(handle-event endpoint :via.endpoint.session-context.update/on-success (assoc event-handler-args :reply %))
                   :on-failure #(handle-event endpoint :via.endpoint.session-context.update/on-failure (assoc event-handler-args :reply %))
                   :on-timeout #(handle-event endpoint :via.endpoint.session-context.update/on-timeout event-handler-args)
                   :timeout defaults/request-timeout))))))))

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

(defn heartbeat
  [endpoint peer-id]
  (when (adapter/opt (endpoint) :heartbeat-enabled)
    (when-let [heartbeat-interval (adapter/opt (endpoint) :heartbeat-interval)]
      (when-let [heartbeat-timer (get-in @(adapter/peers (endpoint)) [peer-id :heartbeat-timer])]
        (timer/cancel heartbeat-timer))
      (swap! (adapter/peers (endpoint)) assoc-in [peer-id :heartbeat-timer]
             (timer/run-after
              (fn []
                (send endpoint peer-id [:via.endpoint/heartbeat])
                (heartbeat endpoint peer-id))
              heartbeat-interval)))))

(defn connect
  ([endpoint peer-address] (connect endpoint peer-address (uuid)))
  ([endpoint peer-address peer-id]
   (when-let [connection (adapter/connect (endpoint) peer-address)]
     ((adapter/handle-connect (endpoint)) endpoint {:id peer-id
                                                    :connection connection
                                                    :role :originator
                                                    :request {:peer-id peer-id
                                                              :peer-address peer-address}})
     (heartbeat endpoint peer-id)
     peer-id)))

(defn send-reply
  [endpoint peer-id request-id {:keys [status body]}]
  (send endpoint peer-id body
        :type :reply
        :params {:status status
                 :request-id request-id}))

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
 (fn [{:keys [endpoint request event]} {:keys [status body] :as message}]
   (when (not status)
     (throw (ex-info "A status must be provided in a :via/reply"
                     {:eg {:status 200}})))
   (if-let [request-id (:request-id request)]
     (send-reply endpoint (:peer-id request) request-id message)
     (handle-event endpoint :via.endpoint.outbound-reply/unhandled
                   {:reply (merge {:type :reply
                                   :reply-to event
                                   :status status}
                                  (when body {:payload body}))}))))

(sfx/reg-fx
 :via.session-context/replace
 (fn [{:keys [endpoint request]} {:keys [session-context sync]}]
   (update-session-context endpoint (:peer-id request) sync (constantly session-context))))

(sfx/reg-fx
 :via.session-context/merge
 (fn [{:keys [endpoint request]} {:keys [session-context sync]}]
   (update-session-context endpoint (:peer-id request) sync #(merge % session-context))))

;;; Event Handlers

(se/reg-event
 :via.session-context/replace
 (fn [_ [_ {:keys [session-context sync]}]]
   {:via.session-context/replace {:session-context session-context
                                  :sync sync}
    :via/reply {:status 200}}))

(se/reg-event
 :via.session-context/merge
 (fn [_ [_ {:keys [session-context sync]}]]
   {:via.session-context/merge {:session-context session-context
                                :sync sync}
    :via/reply {:status 200}}))

(se/reg-event
 :via.endpoint/heartbeat
 (fn [_ _]
   {:via/reply {:status 200}}))

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
    (handle-event endpoint :via.endpoint.inbound-reply/unhandled {:reply reply})))

(defn- send-unknown-event-reply
  [endpoint peer-id message]
  (when-let [request-id (:request-id message)]
    (send-reply endpoint peer-id
                request-id {:status 400
                            :body {:error :via.endpoint/unknown-event}})))

(defn- handle-remote-event
  [endpoint request {:keys [payload] :as message}]
  (let [[event-id & _ :as event] payload]
    (cond
      (not (event? endpoint event-id))
      (do (handle-event endpoint :via.endpoint/unknown-remote-event {:message message})
          (send-unknown-event-reply endpoint (:peer-id request) message))

      (not (se/event? event-id))
      (do (handle-event endpoint :via.endpoint/unknown-signum-event {:message message})
          (send-unknown-event-reply endpoint (:peer-id request) message))

      :else (se/dispatch {:endpoint endpoint
                          :request request
                          :event event} event))))

(defn- handle-message
  [endpoint request message]
  (let [message ((adapter/decode (endpoint)) message)
        request (cond-> request
                  (:request-id message) (assoc :request-id (:request-id message)))]
    (condp = (:type message)
      :event (handle-remote-event endpoint request message)
      :reply (handle-reply endpoint message)
      (handle-event endpoint :via.endpoint/unhandled-message {:message message}))))

(defn- handle-connect
  [endpoint peer]
  (swap! (adapter/peers (endpoint)) assoc (:id peer) peer)
  (handle-event endpoint :via.endpoint.peer/connect peer))

(defn- cancel-reconnect-task
  [endpoint peer-id]
  (when-let [reconnect-task (get-in @(adapter/peers (endpoint)) [peer-id :reconnect-task])]
    (timer/cancel reconnect-task))
  (swap! (adapter/peers (endpoint)) update peer-id dissoc :reconnect-task))


(defn- remove-peer
  [endpoint peer-id]
  (let [peer (get @(adapter/peers (endpoint)) peer-id)]
    (when-let [heartbeat-timer (:heartbeat-timer peer)]
      (timer/cancel heartbeat-timer))
    (cancel-reconnect-task endpoint peer-id)
    (swap! (adapter/peers (endpoint)) dissoc peer-id)
    (handle-event endpoint :via.endpoint.peer/remove peer)))

(defn- reconnect
  ([endpoint peer-address peer-id]
   (reconnect endpoint peer-address peer-id 50))
  ([endpoint peer-address peer-id interval]
   (cancel-reconnect-task endpoint peer-id)
   (if (not (connect endpoint peer-address peer-id))
     (swap! (adapter/peers (endpoint))
            assoc-in [peer-id :reconnect-task]
            (timer/run-after
             #(reconnect endpoint peer-address peer-id
                         (min (* 2 interval)
                              defaults/max-reconnect-interval))
             (min interval defaults/max-reconnect-interval)))
     (cancel-reconnect-task endpoint peer-id))))

(defn- handle-disconnect
  [endpoint {:keys [role request] :as peer}]
  (handle-event endpoint :via.endpoint.peer/disconnect peer)
  (if (and (= :originator role)
           (not (false? (:reconnect peer))))
    (reconnect endpoint (:peer-address request) (:id peer))
    (remove-peer endpoint (:id peer))))

(defn- id-seq->map
  [ids]
  (->> ids
       (map (fn [id]
              [id {}]))
       (into {})))
