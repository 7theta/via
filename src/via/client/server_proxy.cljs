;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.client.server-proxy
  (:require [via.defaults :refer [default-sente-endpoint default-wire-format]]
            [taoensso.sente :refer [make-channel-socket-client!]]
            [taoensso.sente.packers.transit :refer [get-flexi-packer]]
            [com.stuartsierra.component :as component]))

;;; Types

(defrecord ServerProxy [sente-endpoint wire-format]
  component/Lifecycle
  (start [component]
    (if-not (:recv-ch component)
      (let [packer (case wire-format
                     :edn :edn
                     :transit (get-flexi-packer :edn))
            {:keys [ch-recv send-fn state]}
            (make-channel-socket-client! sente-endpoint {:type :auto :packer packer})]
        (assoc component
               :recv-ch ch-recv
               :send-fn send-fn
               :state state))
      component))
  (stop [component]
    (apply dissoc component (keys component))))

;;; Public

(defn server-proxy
  [& {:keys [sente-endpoint wire-format]
      :or {sente-endpoint default-sente-endpoint
           wire-format default-wire-format}}]
  (ServerProxy. sente-endpoint wire-format))
