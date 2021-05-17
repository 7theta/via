;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.defaults)

(def default-via-endpoint "/via")
(def protocol-version 2)
(def request-timeout 10000)
(def heartbeat-interval 30000)
(def max-reconnect-interval 5000)
