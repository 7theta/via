(ns via.util.id)

(defn uuid
  []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))))
