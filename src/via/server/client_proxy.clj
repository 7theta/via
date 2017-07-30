;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.server.client-proxy
  (:require [via.defaults :refer [default-sente-endpoint]]
            [taoensso.sente :refer [make-channel-socket-server!]]
            [taoensso.sente.packers.transit :refer [get-transit-packer]]
            [integrant.core :as ig]))

;;; Public

(declare client-proxy)

(defmethod ig/init-key ::client-proxy [_ {:keys [sente-web-server-adapter]}]
  (client-proxy sente-web-server-adapter))

(defn client-proxy
  [sente-web-server-adapter]
  (let [packer (get-transit-packer :json)
        {:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn connected-uids]}
        (make-channel-socket-server! sente-web-server-adapter {:packer packer})]
    {:ring-ajax-post-fn ajax-post-fn
     :ring-ajax-get-or-ws-handshake-fn ajax-get-or-ws-handshake-fn
     :recv-ch ch-recv
     :send-fn send-fn
     :connected-uids connected-uids}))

(defn send!
  "Asynchronously sends 'message' to the clients connected for 'uid'
  via the 'client-proxy'.

  On slower connections, the messages may be batched for efficiency."
  [client-proxy uid message]
  ((:send-fn client-proxy) uid message))

(defn broadcast!
  "Asynchronously sends 'message' to all connected clients"
  [client-proxy message]
  (doseq [uid (:any @(:connected-uids client-proxy))]
    (send! client-proxy uid message)))
