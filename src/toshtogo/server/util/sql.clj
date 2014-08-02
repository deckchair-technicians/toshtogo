(ns toshtogo.server.util.sql
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [flatland.useful.map :refer [map-vals]]
            [toshtogo.util.json :as json])
  (:import [java.sql BatchUpdateException Timestamp SQLException]
           [clojure.lang Keyword IPersistentMap]
           [org.joda.time DateTime]
           [org.postgresql.util PSQLException PGobject]
           [toshtogo.server.util UniqueConstraintException]))

(defmulti clj->sql class)
(defmethod clj->sql DateTime [v] (Timestamp. (.getMillis v)))
(defmethod clj->sql Keyword [v] (name v))
(defmethod clj->sql IPersistentMap [v]
  (doto (PGobject.)
    (.setType "json")
    (.setValue (json/encode v))))
(defmethod clj->sql :default [v] (identity v))

(defmacro with-exception-conversion [& body]
  `(try
     ~@body
     (catch SQLException e#
       (case (.getSQLState e#)
         "23505"
         (throw (UniqueConstraintException. e#))

         "08004"
         (throw (ex-info (str "Database unavailable- " (.getMessage e#))
                         {:cause     :database-unavailable
                          :sql-state (.getSQLState e#)}
                         e#))

         "3D000"
         (throw (ex-info (str "Database does not exist" (.getMessage e#))
                         {:cause     :database-unavailable
                          :sql-state (.getSQLState e#)}
                         e#))

         (throw e#)))))

(defn execute! [cnxn sql-params]
  (with-exception-conversion
    (sql/execute! cnxn sql-params)))

(defn insert! [cnxn table & records]
  #_(println "Insert" table (ppstr records))
  (with-exception-conversion
    (apply sql/insert!
           cnxn
           table
           (concat (map #(map-vals % clj->sql) records)
                   [:transaction? false]))))

(defn update! [cnxn table set-map where-clause]
  #_(println "Update" table (ppstr [set-map where-clause]))
  (with-exception-conversion
    (try
      (sql/update!
        cnxn
        table
        (map-vals set-map clj->sql)
        (map clj->sql where-clause)
        :transaction? false)
      (catch BatchUpdateException e (throw (.getNextException e))))))
