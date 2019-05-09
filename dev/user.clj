(ns user)

(defn dev
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev :reload)
  (in-ns 'dev))
