;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://www.eclipse.org/legal/epl-v10.html)
;;   which can be found in the LICENSE file at the root of this
;;   distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns ^:figwheel-always via.example.client.core
  (:require [via.example.client.system :refer [system]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :as log]))

(enable-console-print!)

(defn start-system
  []
  (component/start (system)))

(defonce (start-system))
