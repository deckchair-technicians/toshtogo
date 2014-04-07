(ns toshtogo.util.sql
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [flatland.useful.map :refer [map-vals]])
  (:import [java.sql BatchUpdateException Timestamp]
           [clojure.lang Keyword]
           (org.joda.time DateTime)))

(defmulti fix-type class)
(defmethod fix-type DateTime [v] (Timestamp. (.getMillis v)))
(defmethod fix-type Keyword [v] (name v))
(defmethod fix-type :default [v] (identity v))

(defn insert! [cnxn table & records]
  #_(println "Insert" table (ppstr records))
  (apply sql/insert!
         cnxn
         table
         (concat (map #(map-vals % fix-type) records)
                 [:transaction? false])))

(defn update! [cnxn table set-map where-clause]
  #_(println "Update" table (ppstr [set-map where-clause]))
  (try
    (sql/update!
     cnxn
     table
     (map-vals set-map fix-type)
     (map fix-type where-clause)
     :transaction? false)
    (catch BatchUpdateException e (throw (.getNextException e)))))