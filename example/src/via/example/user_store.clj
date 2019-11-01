(ns via.example.user-store
  (:require [via.authenticator :as auth]
            [integrant.core :as ig]))

;; Dummy user-store that will authenticate if the id and password are
;; the same

(defmethod ig/init-key :via.example/user-store [_ _]
  (fn [id]
    {:id id
     :password (auth/hash-password id)}))
