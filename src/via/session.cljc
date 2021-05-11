(ns via.session
  (:require [signum.events :as se]
            [signum.fx :as sfx]
            [distantia.core :refer [diff]]))

(sfx/reg-fx
 :via.session-context/replace
 (fn [{:keys [endpoint request session-context]}]



   ))

(se/reg-event
 :via.session-context/replace
 (fn [{:keys [endpoint request]} [_ session-context]]
   {:via.session-context/replace {:endpoint endpoint
                                  :request request
                                  :session-context session-context}
    :via/status 200
    :via/reply {:status :success}}))
