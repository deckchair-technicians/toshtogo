(ns toshtogo.util.deterministic-representation
  (:require [clojure.walk :refer [prewalk]]
            [toshtogo.util.json :as ttjson])
  (:import (java.util Map Set)
           (clojure.lang PersistentTreeSet PersistentTreeMap Keyword)))

(defmulti deterministic-representation* class)
(defmethod deterministic-representation*
           Map
           [m]
  (doseq [k (keys m)]
    (when (not (instance? Keyword k))
      (throw (IllegalArgumentException. (str "All keys must be keywords in " m)))))

  (PersistentTreeMap/create m))

(defmethod deterministic-representation*
           Set
           [x]

  (throw (IllegalArgumentException. (str "Sets are not supported." x))))

(defmethod deterministic-representation*
           :default
           [x]
  x)

(defn deterministic-representation [x]
  (prewalk deterministic-representation* x))

(defn database-representation [request-body]
  (ttjson/encode request-body))