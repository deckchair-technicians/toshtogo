(ns toshtogo.sql
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [flatland.useful.map :refer [map-vals]]
            [clojure.set :refer [difference]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]))

(defn missing-keys-exception [m missing-keys]
  (IllegalArgumentException.
              (str "Missing: ["
                   (clojure.string/join  ", " (map (comp str name) missing-keys))
                   "] in "
                   (if-not m "nil"))))

(defn assert-keys [m expected-keys]
  (let [missing-keys (difference (set expected-keys) (set (keys m)))]
    (when (seq missing-keys)
      (throw (missing-keys-exception m missing-keys)))
    m))

(def param-pattern #":([0-9A-Za-z\-_]+)")

(defmulti fix-type class)
(defmethod fix-type org.joda.time.DateTime [v] (java.sql.Date. (.getMillis v)))
(defmethod fix-type :default [v] (identity v))

(defn fix-types [params]
  (map-vals params fix-type))

(defn named-params
  [sql params]
  (let [param-usages         (re-seq param-pattern sql)
        keywords-usage-order (map (comp keyword second) param-usages)
        type-fixed-params    (fix-types params)]
    (assert-keys params keywords-usage-order)
    (vec (cons (str/replace sql param-pattern "?")
               (map type-fixed-params keywords-usage-order)))))

(defn insert! [cnxn table & records]
  (apply sql/insert! cnxn table (map fix-types records)))

(defn query [cnxn sql params]
  "Takes some sql including references to parameters in the form
   :parameter-name and a map of named parameters"
  (let [fixed-params (named-params sql params)]
    (pprint fixed-params)
    (sql/query cnxn fixed-params)))
