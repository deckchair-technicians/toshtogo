(ns toshtogo.sql
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [flatland.useful.map :refer [map-vals]]
            [clojure.set :refer [difference]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
    (:import [java.sql PreparedStatement]))

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
(defmethod fix-type org.joda.time.DateTime [v] (java.sql.Timestamp. (.getMillis v)))
(defmethod fix-type :default [v] (identity v))

(defn fix-types [params]
  (map-vals params fix-type))

(defn extract-params [sql]
  (let [param-usages         (re-seq param-pattern sql)
        param-usages (map (comp keyword second) param-usages)
        normalised-sql       (str/replace sql param-pattern "?")]
    [normalised-sql param-usages]))

(defn param-values [param-usages params]
  (map (fix-types params) param-usages))

(defn named-params
  [sql params]
  (let [[normalised-sql param-usages] (extract-params sql)]
    (assert-keys params param-usages)
    (vec (cons
          normalised-sql
          (param-values param-usages params)))))

(defn insert! [cnxn table & records]
  (apply sql/insert!
         cnxn
         table
         (map fix-types records)))

(defn query [cnxn sql params]
  "Takes some sql including references to parameters in the form
   :parameter-name and a map of named parameters"
  (let [fixed-params (named-params sql params)]
    (sql/query cnxn fixed-params)))
