;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.events
  (:require [via.endpoint :as via]
            [signum.events :as se]
            [integrant.core :as ig]))

(defmethod ig/init-key :via/events
  [_ {:keys [endpoint]}]


  )

(defmethod ig/halt-key! :via/events
  [_ {:keys [endpoint ]}]


  )
