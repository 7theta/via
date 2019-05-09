(ns example.ring-handler
  (:require [via.defaults :refer [default-via-endpoint]]
            [compojure.core :as compojure :refer [GET POST]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.defaults :as ring-defaults]
            [integrant.core :as ig]))

(defmethod ig/init-key :example/ring-handler [_ {:keys [via-handler]}]
  (-> (compojure/routes
       (GET "/" req-req (response/content-type
                         (response/resource-response "public/index.html")
                         "text/html"))
       (GET  default-via-endpoint ring-req (via-handler ring-req))
       (route/resources "/"))
      (ring-defaults/wrap-defaults ring-defaults/site-defaults)))
