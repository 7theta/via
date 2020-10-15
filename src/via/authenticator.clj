;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.authenticator
  (:require [via.events :refer [reg-event-via]]
            [via.endpoint :as via]
            [signum.interceptors :refer [->interceptor]]
            [buddy.hashers :as bh]
            [buddy.sign.jwt :as jwt]
            [buddy.core.nonce :as bn]
            [tick.core :as t]
            [integrant.core :as ig]))

(declare validate-token authenticate)

(def interceptor nil)

;; Initializes the authenticator with a 'query-fn' and an optional 'secret'.
;; The 'query-fn' must take a id for the user and return a hash map containing
;; ':id' and ':password' (hashed) keys or a nil.
;; If a secret is not provided, a random secret is generated on initialization.

(def default-secret (bn/random-bytes 32))

(defmethod ig/init-key :via/authenticator [_ {:keys [query-fn secret endpoint]
                                              :or {secret default-secret}}]
  (let [authenticator {:query-fn query-fn :secret secret :endpoint endpoint}
        sub-key (via/subscribe endpoint
                               {:connection-context-changed
                                (fn [connection-context]
                                  (let [token (get-in connection-context [:via/authenticator :token])]
                                    {:via/replace-tags
                                     (when-let [uid (:id (validate-token authenticator token))]
                                       #{uid})}))})
        authenticator (merge authenticator {:sub-key sub-key})]
    (alter-var-root
     #'interceptor
     (constantly
      (->interceptor
       :id :via.authenticator/interceptor
       :before (fn [context]
                 (let [token (get-in context [:coeffects :client :connection-context :via/authenticator :token])]
                   (if (validate-token authenticator token)
                     context
                     (assoc context
                            :queue []   ; Stop any further execution
                            :effects {:via/status 403
                                      :via/reply {:error :invalid-token :token token}})))))))
    (reg-event-via
     :via/id-password-login
     (fn [context [_ {:keys [id password]}]]
       (if-let [user (authenticate authenticator id password)]
         {:via/merge-connection-context {:via/authenticator {:token (:token user)}}
          :via/reply user
          :via/status 200}
         {:via/reply {:error :invalid-credentials}
          :via/status 403})))
    (reg-event-via
     :via/logout
     (fn [context _]
       {:via/merge-connection-context {:via/authenticator nil}
        :via/reply true
        :via/status 200}))
    authenticator))


(defmethod ig/halt-key! :via/authenticator
  [_ {:keys [sub-key endpoint]}]
  (via/dispose endpoint sub-key))

(defn authenticate
  "Authenticates the user identified by `id` and `password` and returns a hash map
  with `:id` and `:token` (JWT token) in addition to any other data returned by
  the query-fn if the authentication is successful. A nil is returned if the authentication
  fails."
  [{:keys [query-fn secret] :as authenticator} id password & {:keys [expiry] :or {expiry 24}}]
  (try
    (when-let [user (query-fn id)]
      (when (bh/check password (:password user))
        (let [user (dissoc user :password)]
          (assoc user :token (jwt/encrypt (assoc user :exp (t/+ (t/now) (t/new-duration expiry :hours)))
                                          secret)))))
    (catch Exception _ nil)))

(defn validate-token
  "Validates the `token` using `authenticator`"
  [{:keys [secret] :as authenticator} token]
  (try
    (when token (jwt/decrypt token secret))
    (catch Exception _ nil)))

(defn hash-password
  "Hashes `password` using the default algorithm (currently :bcrypt+sha512)"
  [password]
  (bh/derive password))
