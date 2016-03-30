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
  (:require [via.defaults :refer [default-sente-endpoint default-wire-format]]
            [taoensso.sente :refer [make-channel-socket-server!]]
            [taoensso.sente.packers.transit :refer [get-flexi-packer]]
            [com.stuartsierra.component :as component]))

;;; Types

(defrecord ClientProxy [sente-web-server-adapter wire-format]
  component/Lifecycle
  (start [component]
    (if-not (:recv-ch component)
      (let [packer (case wire-format
                     :edn :edn
                     :transit (get-flexi-packer :edn))
            {:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn connected-uids]}
            (make-channel-socket-server! sente-web-server-adapter {:packer packer})]
        (assoc component
               :ring-ajax-post-fn ajax-post-fn
               :ring-ajax-get-or-ws-handshake-fn ajax-get-or-ws-handshake-fn
               :recv-ch ch-recv
               :send-fn send-fn
               :connected-uids connected-uids))
      component))
  (stop [component]
    (apply dissoc component (keys component))))

;;; Public

(defn client-proxy
  [sente-web-server-adapter & {:keys [wire-format]
                               :or {wire-format default-wire-format}}]
  (ClientProxy. sente-web-server-adapter wire-format))
