;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.example.server.web-handler
  (:require [via.defaults :refer [default-sente-endpoint]]
            [compojure.core :as compojure :refer [GET POST]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.middleware.defaults :as ring-defaults]))

;;; Public

(defn sente-ring-handler
  [client-proxy]
  (let [ring-ajax-get-or-ws-handshake (:ring-ajax-get-or-ws-handshake-fn client-proxy)
        ring-ajax-post (:ring-ajax-post-fn client-proxy)
        routes (compojure/routes
                (GET "/" req-req (response/content-type
                                  (response/resource-response "public/index.html")
                                  "text/html"))
                (GET  default-sente-endpoint ring-req (ring-ajax-get-or-ws-handshake ring-req))
                (POST default-sente-endpoint ring-req (ring-ajax-post ring-req))
                (route/resources "/"))]
    (ring-defaults/wrap-defaults routes ring-defaults/site-defaults)))
