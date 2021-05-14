;;   Copyright (c) 7theta. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   MIT License (https://opensource.org/licenses/MIT) which can also be
;;   found in the LICENSE file at the root of this distribution.
;;
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any others, from this software.

(ns via.util.re-frame
  (:require [re-frame.registrar :as rfr]
            [re-frame.core :as rf]))

(defn adapter
  [[id & _ :as query] ref]
  #_(rf/reg-sub
     id
     (fn [db query-v]
       (swap! subscriptions conj query-v)
       (get-in db (path query-v)))))
