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
            #?(:cljs [utilis.js :as j])
            [via.util.promise :as p]
            [via.adapter :as adapter]
            #?(:clj [via.telemetry])
            [via.util.id :refer [uuid]]
            [via.defaults :as defaults]
            [signum.fx :as sfx]
            [signum.events :as se]
            [signum.subs :as ss]
            [tempus.core :as t]
            [tempus.duration :as td]
            [tempus.transit :as tt]
            [cognitect.transit :as transit]
            [utilis.fn :refer [fsafe]]
            [utilis.map :refer [map-vals]]
            [utilis.timer :as timer]
            [integrant.core :as ig]
            [clojure.set :refer [union difference]]
            [clojure.string :as st]
            #?(:clj [metrics.histograms :as histograms])
            #?(:clj [metrics.meters :as meters])
            #?(:clj [metrics.counters :as counters])
            #?(:clj [metrics.timers :as timers])
            #?(:clj [clojure.tools.logging :as log]))
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream])))

(defonce endpoints (atom #{}))

(declare encode-message decode-message
         connect disconnect
         handle-connect handle-disconnect
         handle-message handle-event
         normalize-namespace
         #?(:clj metrics))

(defmethod ig/init-key :via/endpoint
  [_ {:keys [peers
             exports
             transit-handlers
             event-listeners
             adapter
             adapter-options
             heartbeat-interval
             heartbeat-timeout
             heartbeat-enabled
             heartbeat-fail-threshold]
      :or {adapter #?(:clj aleph/websocket-adapter
                      :cljs haslett/websocket-adapter)
           heartbeat-interval defaults/heartbeat-interval
           heartbeat-fail-threshold 1
           heartbeat-timeout defaults/request-timeout
           heartbeat-enabled true}}]
  (try (let [{:keys [events subs namespaces]} exports
             #?@(:clj [metrics (metrics)] :cljs [metrics {}])
             endpoint (adapter (merge
                                #?(:clj {:metrics metrics})
                                {:event-listeners (atom (map-vals (fn [handler]
                                                                    {(uuid) handler})
                                                                  event-listeners))
                                 :exports (atom {:subs (set subs)
                                                 :events (set (concat events
                                                                      [:via.session-context/replace
                                                                       :via.session-context/merge
                                                                       :via.endpoint/heartbeat]))
                                                 :namespaces (set namespaces)})
                                 :peers (atom {})
                                 :requests (atom {})
                                 :context (atom {})
                                 :handle-message (fn [& args] (apply handle-message args))
                                 :handle-disconnect (fn [& args] (apply handle-disconnect args))
                                 :handle-connect (fn [& args] (apply handle-connect args))
                                 :decode (partial decode-message metrics {:handlers (merge (:read tt/handlers) (:read transit-handlers))})
                                 :encode (partial encode-message metrics {:handlers (merge (:write tt/handlers) (:write transit-handlers))})
                                 :heartbeat-interval heartbeat-interval
                                 :heartbeat-enabled heartbeat-enabled
                                 :heartbeat-fail-threshold heartbeat-fail-threshold
                                 :heartbeat-timeout heartbeat-timeout}
                                adapter-options))]
         (swap! endpoints conj endpoint)
         (doseq [peer-address peers]
           (connect endpoint peer-address))
         endpoint)
       #?(:clj (catch Exception e
                 (log/error "Exception starting :via/endpoint" e)
                 (throw e))
          :cljs (catch js/Error e
                  (js/console.error "Exception starting :via/endpoint" e)
                  (throw e)))))

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
  (#?@(:clj [timers/time! (adapter/static-metric (endpoint) :via.endpoint.send/timer)]
       :cljs [identity])
   (do (when (not peer-id)
         (throw (ex-info "No peer-id provided" {:message message
                                                :peer-id peer-id
                                                :type type
                                                :params params
                                                :timeout timeout})))
       #?(:clj (meters/mark! (adapter/static-metric (endpoint) :via.endpoint.throughput.messages-sent/meter)))
       (let [message (merge (when type {:type type})
                            (when message {:body message})
                            params)]
         (if (or on-success on-failure on-timeout)
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
                                                 (log/error "Unhandled exception in timeout handler" e))
                                          :cljs (catch js/Error e
                                                  (js/console.error "Unhandled exception in timeout handler" e)))))
                             timeout)
                     :timeout timeout
                     :timestamp (t/now)
                     :peer-id peer-id})
             (adapter/send (endpoint) peer-id ((adapter/encode (endpoint)) message)))
           (adapter/send (endpoint) peer-id ((adapter/encode (endpoint)) message)))))))

(defn send-to-tag
  [endpoint tag message & {:keys [type timeout params
                                  on-success
                                  on-failure
                                  on-timeout timeout]
                           :or {type :event
                                timeout 30000}}]
  (doseq [peer-id (->> @(adapter/peers (endpoint))
                       (filter (fn [[peer-id {:keys [tags]}]]
                                 (get tags tag)))
                       (map first))]
    (send endpoint peer-id message
          :type type
          :timeout timeout
          :params params
          :on-success on-success
          :on-failure on-failure
          :on-timeout on-timeout
          :timeout timeout)))

(defn broadcast
  "Asynchronously sends `message` to all connected clients"
  [endpoint message]
  (doseq [[peer-id _] @(adapter/peers (endpoint))]
    (send endpoint peer-id message)))

(defn disconnect
  ([endpoint peer-id]
   (disconnect endpoint peer-id false))
  ([endpoint peer-id reconnect]
   (adapter/disconnect (endpoint) peer-id reconnect)))

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
         (handle-event endpoint :via.endpoint.session-context/change session-context)
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

(defn export-sub
  [endpoint sub-id]
  (swap! (adapter/exports (endpoint)) update :subs conj sub-id))

(defn sub?
  [endpoint sub-id]
  (boolean
   (or (get-in @(adapter/exports (endpoint)) [:subs sub-id])
       (some #(= (normalize-namespace %)
                 (normalize-namespace (ss/namespace sub-id)))
             (:namespaces @(adapter/exports (endpoint)))))))

(defn export-event
  [endpoint event-id]
  (swap! (adapter/exports (endpoint)) update :events conj event-id))

(defn event?
  [endpoint event-id]
  (boolean
   (or (get-in @(adapter/exports (endpoint)) [:events event-id])
       (some #(= (normalize-namespace %)
                 (normalize-namespace (se/namespace event-id)))
             (:namespaces @(adapter/exports (endpoint)))))))

(defn heartbeat
  [endpoint peer-id]
  (when (adapter/opt (endpoint) :heartbeat-enabled)
    (when-let [heartbeat-timer (get-in @(adapter/peers (endpoint)) [peer-id :heartbeat-timer])]
      (timer/cancel heartbeat-timer))
    (let [{:keys [heartbeat-interval
                  heartbeat-timeout
                  heartbeat-fail-threshold]} (adapter/opts (endpoint))
          send-heartbeat (atom nil)
          sent-timestamp (atom (t/now))
          handle-failure (fn [reason]
                           (let [peers (swap! (adapter/peers (endpoint))
                                              (fn [peers]
                                                (if (contains? peers peer-id)
                                                  (update-in peers [peer-id :heartbeat-failure-count]
                                                             (fn [failure-count]
                                                               (inc (or failure-count 0))))
                                                  peers)))
                                 failure-count (get-in peers [peer-id :heartbeat-failure-count])]
                             (if (>= failure-count heartbeat-fail-threshold)
                               (do #?(:clj (log/debug ":via.endpoint/heartbeat failed. Reconnecting to peer." {:peer-id peer-id})
                                      :cljs (js/console.debug ":via.endpoint/heartbeat failed. Reconnecting to peer." #js {:peer-id peer-id}))
                                   (disconnect endpoint peer-id true))
                               (@send-heartbeat))))
          handle-success (fn []
                           (let [peers (swap! (adapter/peers (endpoint))
                                              (fn [peers]
                                                (if (contains? peers peer-id)
                                                  (assoc-in peers [peer-id :heartbeat-failure-count] 0)
                                                  peers)))]
                             (@send-heartbeat)))]
      (reset! send-heartbeat (fn []
                               (let [elapsed (- (t/into :long (t/now))
                                                (t/into :long @sent-timestamp))
                                     heartbeat-interval (max 0 (- heartbeat-interval elapsed))]
                                 (swap! (adapter/peers (endpoint)) assoc-in [peer-id :heartbeat-timer]
                                        (timer/run-after
                                         (fn []
                                           (reset! sent-timestamp (t/now))
                                           (send endpoint peer-id [:via.endpoint/heartbeat]
                                                 :timeout heartbeat-timeout
                                                 :on-success handle-success
                                                 :on-failure (partial handle-failure :failure)
                                                 :on-timeout (partial handle-failure :timeout)))
                                         heartbeat-interval)))))
      (@send-heartbeat))))

(defn connect
  ([endpoint peer-address]
   (connect endpoint peer-address (uuid)))
  ([endpoint peer-address peer-id]
   (let [peer {:id peer-id
               :role :originator
               :request {:peer-id peer-id
                         :peer-address peer-address}}]
     (swap! (adapter/peers (endpoint)) assoc (:id peer) peer)
     #?(:clj (if-let [connection (adapter/connect (endpoint) peer-address)]
               (do ((adapter/handle-connect (endpoint)) endpoint (merge peer {:connection connection}))
                   (heartbeat endpoint peer-id)
                   peer-id)
               (do (swap! (adapter/peers (endpoint)) dissoc (:id peer))
                   nil))
        :cljs (-> (endpoint)
                  (adapter/connect peer-address)
                  (j/call :then (fn [connection]
                                  ((adapter/handle-connect (endpoint)) endpoint (merge peer {:connection connection}))
                                  (heartbeat endpoint peer-id)
                                  peer-id))
                  (j/call :catch (fn [error]
                                   (swap! (adapter/peers (endpoint)) dissoc (:id peer))
                                   (throw error))))))))

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
                                  (when body {:body body}))}))))

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
  [metrics handlers data]
  #?(:clj (timers/time!
           (-> metrics :static :via.endpoint.encode-message/timer)
           (let [message (try
                           (let [out (ByteArrayOutputStream. 4096)]
                             (transit/write (transit/writer out :json handlers) data)
                             (.toString out))
                           (catch Exception e
                             (log/error "Exception occurred encoding message" data e)))]
             (histograms/update!
              (-> metrics :static :via.endpoint.outbound.message-size/histogram)
              (count message))
             message))
     :cljs (try (transit/write (transit/writer :json handlers) data)
                (catch js/Error e
                  (js/console.error "Exception occurred encoding message" (clj->js data) e)))))

(defn- decode-message
  [metrics handlers ^String data]
  #?(:clj (timers/time!
           (-> metrics :static :via.endpoint.decode-message/timer)
           (try (do (histograms/update!
                     (-> metrics :static :via.endpoint.inbound.message-size/histogram)
                     (count data))
                    (let [in (ByteArrayInputStream. (.getBytes data))]
                      (transit/read (transit/reader in :json handlers))))
                (catch Exception e
                  (log/error "Exception occurred decoding message" data e))))
     :cljs (try (transit/read (transit/reader :json handlers) data)
                (catch js/Error e
                  (js/console.error "Exception occurred encoding message" data e)))))

(defn- handle-reply
  [endpoint reply]
  (if-let [request (get @(adapter/requests (endpoint)) (:request-id reply))]
    (do ((fsafe timer/cancel) (:timer request))
        (let [f (get {200 (:on-success request)} (:status reply) (:on-failure request))]
          (try ((fsafe f) (select-keys reply [:status :body]))
               #?(:clj (catch Exception e
                         (log/error "Unhandled exception in reply handler" e))
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
  [endpoint request {:keys [body] :as message}]
  #?(:clj (meters/mark! (adapter/static-metric (endpoint) :via.endpoint.throughput.inbound-events/meter)))
  (let [[event-id & _ :as event] body]
    (cond
      (or (not (event? endpoint event-id))
          (not (se/event? event-id)))
      (do (handle-event endpoint :via.endpoint/unknown-event {:message message})
          (send-unknown-event-reply endpoint (:peer-id request) message))

      :else (se/dispatch {:endpoint endpoint
                          :request request
                          :event event} event))))

(defn- handle-message
  [endpoint request message]
  (#?@(:clj [timers/time! (adapter/static-metric (endpoint) :via.endpoint.handle-message/timer)]
       :cljs [identity])
   #?(:clj (meters/mark! (adapter/static-metric (endpoint) :via.endpoint.throughput.messages-received/meter)))
   (let [message ((adapter/decode (endpoint)) message)
         request (cond-> request
                   (:request-id message) (assoc :request-id (:request-id message)))]
     (condp = (:type message)
       :event (handle-remote-event endpoint request message)
       :reply (handle-reply endpoint message)
       (handle-event endpoint :via.endpoint/unhandled-message {:message message})))))

(defn- handle-connect
  [endpoint peer]
  (let [peers (swap! (adapter/peers (endpoint)) assoc (:id peer)
                     (assoc peer
                            #?@(:clj [:active-timer (-> (endpoint)
                                                        (adapter/static-metric :via.endpoint.peer.connection-duration/timer)
                                                        (timers/start))])
                            :connected-timestamo (t/now)))]
    #?(:clj (histograms/update!
             (adapter/static-metric (endpoint) :via.endpoint.active-peers/histogram)
             (count peers)))
    (handle-event endpoint :via.endpoint.peer/connected peer)))

(defn- cancel-reconnect-task
  [endpoint peer-id]
  (when-let [reconnect-task (get-in @(adapter/peers (endpoint)) [peer-id :reconnect-task])]
    (timer/cancel reconnect-task))
  (swap! (adapter/peers (endpoint)) update peer-id dissoc :reconnect-task))

(defn- remove-peer
  [endpoint peer-id]
  (let [peer (get @(adapter/peers (endpoint)) peer-id)]
    #?(:clj (when-let [active-timer (:active-timer peer)]
              (timers/stop active-timer)))
    (when-let [heartbeat-timer (:heartbeat-timer peer)]
      (timer/cancel heartbeat-timer))
    (cancel-reconnect-task endpoint peer-id)
    (let [peers (swap! (adapter/peers (endpoint)) dissoc peer-id)]
      #?(:clj (histograms/update!
               (adapter/static-metric (endpoint) :via.endpoint.active-peers/histogram)
               (count peers)))
      (handle-event endpoint :via.endpoint.peer/removed peer))))

(defn- reconnect
  ([endpoint peer-address peer-id]
   (reconnect endpoint peer-address peer-id 50))
  ([endpoint peer-address peer-id interval]
   (let [on-connect-failed (fn []
                             (swap! (adapter/peers (endpoint))
                                    assoc-in [peer-id :reconnect-task]
                                    (timer/run-after
                                     #(reconnect endpoint peer-address peer-id
                                                 (min (* 2 interval)
                                                      defaults/max-reconnect-interval))
                                     (min interval defaults/max-reconnect-interval))))]
     (cancel-reconnect-task endpoint peer-id)
     #?(:clj (if (not (connect endpoint peer-address peer-id))
               (on-connect-failed)
               (cancel-reconnect-task endpoint peer-id))
        :cljs (-> endpoint
                  (connect peer-address peer-id)
                  (j/call :then (fn [_] (cancel-reconnect-task endpoint peer-id)))
                  (j/call :catch (fn [_] (on-connect-failed))))))))

(defn- handle-disconnect
  [endpoint {:keys [role request] :as peer}]
  (handle-event endpoint :via.endpoint.peer/disconnected peer)
  (if (and (= :originator role)
           (not (false? (:reconnect peer))))
    (reconnect endpoint (:peer-address request) (:id peer))
    (remove-peer endpoint (:id peer))))

(def namespace-type (type *ns*))

(defn namespace?
  [ns]
  (instance? namespace-type ns))

(defn- normalize-namespace
  [ns]
  (cond
    (nil? ns) nil
    (string? ns) (keyword
                  (-> (str ns)
                      (st/replace #"^:" "")
                      (st/replace #"/" ".")))
    (keyword? ns) (normalize-namespace (str ns))
    (namespace? ns) #?(:clj (keyword (.getName ns))
                       :cljs (keyword (j/call ns :getName)))
    :else (throw (ex-info "Can't normalize namespace" {:ns ns}))))

#?(:clj (defn- metrics
          []
          (let [id (uuid)
                keys (atom #{})
                key->metric (fn [key & {:keys [name-suffix]}]
                              (let [segments (st/split (namespace key) #"\.")
                                    metric-group (str id "." (st/join "." (drop-last segments)))
                                    metric-name (str (last segments) (when name-suffix (str "." name-suffix)))
                                    metric-type (name key)
                                    metric-title [metric-group metric-type metric-name]]
                                (swap! keys conj [key metric-title])
                                (condp = (keyword metric-type)
                                  :counter (counters/counter metric-title)
                                  :timer (timers/timer metric-title)
                                  :histogram (histograms/histogram metric-title)
                                  :meter (meters/meter metric-title)
                                  (throw (ex-info "Unrecognized metric type" {:key key})))))]
            {:keys keys
             :dynamic (fn [key instance-id]
                        ;; e.g.
                        ;; :via.endpoint.subs.inbound/counter
                        ;; :via.endpoint.events.dispatch/counter
                        ;; :via.endpoint.handle-event/counter
                        (key->metric key :name-suffix instance-id))
             :static (->> [;; time the durations that a peer is connected for
                           :via.endpoint.peer.connection-duration/timer

                           ;; time certain critical functions on peers
                           :via.endpoint.peer.subscribe-inbound/timer
                           :via.endpoint.peer.dispose-inbound/timer
                           :via.endpoint.peer.subscribe-outbound/timer
                           :via.endpoint.peer.dispose-outbound/timer

                           ;; time certain critical functions on endpoint
                           :via.endpoint.send/timer
                           :via.endpoint.encode-message/timer
                           :via.endpoint.decode-message/timer
                           :via.endpoint.handle-message/timer

                           ;; track some historical values
                           :via.endpoint.active-peers/histogram
                           :via.endpoint.outbound.message-size/histogram
                           :via.endpoint.inbound.message-size/histogram

                           ;; throughput
                           :via.endpoint.throughput.inbound-subs/meter
                           :via.endpoint.throughput.inbound-events/meter
                           :via.endpoint.throughput.messages-received/meter
                           :via.endpoint.throughput.messages-sent/meter

                           ;; [latency] - time between message received and a reply being eventually sent
                           :via.endpoint.latency.send-reply/timer ;; TODO

                           ;; [latency] - queue wait time / how long does a message sit in the queue before
                           ;;             'handle-message' is eventually called.
                           :via.endpoint.latency.queue-wait-time/timer ;; TODO

                           ]
                          (map (fn [key] [key (key->metric key)]))
                          (into {}))})))
