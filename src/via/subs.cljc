;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.subs
  (:require [via.endpoint :as via]
            [via.adapter :as adapter]
            [via.util.id :refer [uuid]]
            [distantia.core :refer [patch]]
            [signum.events :as se]
            [signum.subs :as ss]
            [signum.fx :as sfx]
            [signum.signal :as sig]
            [tempus.core :as t]
            [distantia.core :refer [diff]]
            [integrant.core :as ig]))

(declare dispose-inbound
         dispose-outbound
         subscribe-inbound
         sub-key)

#?(:clj (def exception-lock (Object.)))

(def subscription-lock #?(:cljs (js/Object.) :clj (Object.)))

(defmethod ig/init-key :via/subs
  [_ {:keys [endpoint]}]
  (let [inbound-subs (atom {})
        outbound-subs (atom {})]
    (via/merge-context endpoint {::inbound-subs inbound-subs
                                 ::outbound-subs outbound-subs})
    (via/reg-event endpoint :via.subs/subscribe)
    (via/reg-event endpoint :via.subs/dispose)
    (via/reg-event endpoint :via.subs.signal/updated)
    {:endpoint endpoint
     :inbound-subs inbound-subs
     :outbound-subs outbound-subs
     :listener-id (via/add-event-listener endpoint
                                          :close (fn [{:keys [peer-id]}]
                                                   (dispose-outbound endpoint peer-id)
                                                   (dispose-inbound endpoint peer-id)))}))

(defmethod ig/halt-key! :via/subs
  [_ {:keys [endpoint listener-id inbound-subs outbound-subs]}]
  (doseq [[query-v peer-id] (keys @inbound-subs)]
    (dispose-inbound endpoint peer-id query-v))
  (doseq [[peer-id query-v] (keys @outbound-subs)]
    (dispose-outbound endpoint peer-id query-v))
  (via/remove-event-listener endpoint :close listener-id))

(defn subscribe
  ([endpoint peer-id query] (subscribe endpoint peer-id query nil))
  ([endpoint peer-id [query-id & _ :as query] default]
   (let [outbound-subs (::outbound-subs @(adapter/context (endpoint)))]
     (locking subscription-lock
       (when (not (ss/sub? query-id))
         (ss/reg-sub
          query-id
          (fn [query-v]
            (let [signal (sig/signal default)]
              (swap! outbound-subs assoc (sub-key peer-id query-v)
                     {:state (atom {:window []
                                    :sn 0
                                    :updated nil})
                      :signal signal})
              (let [reply-handler (fn [event-key]
                                    (fn [& args]
                                      (via/handle-event endpoint event-key
                                                        (merge (when (seq args)
                                                                 {:reply (first args)})
                                                               {:peer-id peer-id
                                                                :query-v query-v
                                                                :default default}))))]
                (via/send endpoint peer-id
                          [:via.subs/subscribe
                           {:query-v query-v
                            :callback [:via.subs.signal/updated (sub-key peer-id query-v)]}]
                          :on-success (reply-handler :via.subs.subscribe/success)
                          :on-failure (reply-handler :via.subs.subscribe/failure)
                          :on-timeout (reply-handler :via.subs.subscribe/timeout)
                          :timeout 10000))
              signal))
          (fn [signal _] @signal))
         true))
     (ss/subscribe query))))

;;; Private

(defn- sub-key
  [peer-id query-v]
  [peer-id query-v])

(defn- dispose-inbound
  ([endpoint peer-id]
   (doseq [[query-v _] (->> @(::inbound-subs @(adapter/context (endpoint)))
                            keys
                            (filter #(= peer-id (second %))))]
     (dispose-inbound endpoint peer-id query-v)))
  ([endpoint peer-id query-v]
   (boolean
    (let [inbound-subs (::inbound-subs @(adapter/context (endpoint)))]
      (when-let [{:keys [signal watch-key]} (get @inbound-subs (sub-key peer-id query-v))]
        (remove-watch signal watch-key)
        (swap! inbound-subs dissoc (sub-key peer-id query-v))
        true)))))

(defn dispose-outbound
  ([endpoint peer-id]
   (doseq [[query-v _] (->> @(::outbound-subs @(adapter/context (endpoint)))
                            keys
                            (filter #(= peer-id (second %))))]
     (dispose-outbound endpoint peer-id query-v)))
  ([endpoint peer-id query-v]
   (boolean
    (let [outbound-subs (::outbound-subs @(adapter/context (endpoint)))
          reply-handler (fn [event-key]
                          (fn [& args]
                            (via/handle-event endpoint event-key
                                              (merge (when (seq args)
                                                       {:reply (first args)})
                                                     {:peer-id peer-id
                                                      :query-v query-v}))))]
      (via/send endpoint peer-id
                [:via.subs/dispose {:query-v query-v}]
                :on-success (reply-handler :via.subs.dispose/success)
                :on-failure (reply-handler :via.subs.dispose/failure))
      (swap! outbound-subs dissoc (sub-key peer-id query-v))
      true))))

(defn- subscribe-inbound
  [endpoint request [query-id & _ :as query-v] callback]
  (if-let [signal (binding [ss/*context* {:endpoint endpoint
                                          :request request}]
                    (ss/subscribe query-v))]
    (let [peer-id (:peer-id request)
          sequence-number (atom (long 0))
          watch-key (str ":via-" query-v "(" peer-id ")")
          send-value! #(try (via/send endpoint peer-id
                                      (conj (vec callback)
                                            {:query-v query-v
                                             :change %
                                             :sn (swap! sequence-number inc)}))
                            true
                            (catch #?(:clj Exception :cljs js/Error) e
                              #?(:clj (locking exception-lock
                                        (println :via/send-value "->" peer-id "\n" e))
                                 :cljs (js/console.error ":via/send-value" "->" peer-id "\n" e))
                              (dispose-inbound endpoint peer-id)
                              false))]
      (swap! (::inbound-subs @(adapter/context (endpoint)))
             assoc [query-v peer-id] {:signal signal
                                      :watch-key watch-key})
      (add-watch signal watch-key (fn [_ _ old new]
                                    (when (not= old new)
                                      (send-value! (if (or (and (map? new) (map? old))
                                                           (and (vector? new) (vector? old)))
                                                     [:p (diff old new)]
                                                     [:v new])))))
      (send-value! [:v @signal]))
    (do (via/handle-event endpoint :via.subs.inbound-subscribe/no-signal
                          {:query-v query-v
                           :callback callback})
        false)))

(defn- split-contiguous
  [last-sn window]
  (let [window (sort-by :sn window)
        state (volatile! last-sn)
        result (partition-by #(= (:sn %) (vswap! state inc)) window)]
    (cond
      (= (count result) 2) result
      (= (inc last-sn) (:sn (ffirst result))) [(first result) nil]
      :else [nil (first result)])))

(defn- write-message!
  [signal {:keys [change]}]
  (sig/alter! signal
              (fn [value]
                (if (= :v (first change))
                  (second change)
                  (patch value (second change))))))

(sfx/reg-fx
 :via.subs.signal/updated
 (fn [{:keys [endpoint]} {:keys [sub-key msg]}]
   (let [outbound-subs (::outbound-subs @(adapter/context (endpoint)))]
     (if-let [{:keys [signal state]} (get @outbound-subs sub-key)]
       (let [{:keys [query-v change sn]} msg
             {:keys [window last-sn] :or {last-sn 0}} @state
             [contiguous-messages window] (->> msg
                                               (conj window)
                                               (split-contiguous last-sn))]
         (reduce write-message! signal contiguous-messages)
         (reset! state {:window window
                        :updated (t/into :long (t/now))
                        :last-sn (-> contiguous-messages last :sn)}))
       (via/handle-event endpoint :via.subs.signal.updated/no-signal
                         {:sub-key sub-key
                          :msg msg})))))

(se/reg-event
 :via.subs.signal/updated
 (fn [_ [_ sub-key msg]]
   {:via.subs.signal/updated {:sub-key sub-key
                              :msg msg}}))

(se/reg-event
 :via.subs/subscribe
 (fn [{:keys [endpoint request]} [_ {:keys [query-v callback]}]]
   (if (subscribe-inbound endpoint request query-v callback)
     {:via/reply {:body {:status :success}
                  :status 200}}
     {:via/reply {:status 400
                  :body {:status :error
                         :error :invalid-subscription
                         :query-v query-v}}})))

(se/reg-event
 :via.subs/dispose
 (fn [{:keys [endpoint request]} [_ {:keys [query-v]}]]
   (if (dispose-inbound endpoint (:peer-id request) query-v)
     {:via/reply {:status 200
                  :body {:status :success}}}
     {:via/reply {:status 400
                  :body {:status :error
                         :error :invalid-subscription
                         :query-v query-v}}})))
