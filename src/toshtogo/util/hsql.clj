(ns toshtogo.util.hsql
  (:require [clj-time.coerce :refer [to-timestamp from-sql-date]]
            [clojure.java.jdbc :as sql]
            [clojure.math.numeric-tower :refer [ceil]]
            [flatland.useful.map :as mp]
            [clojure.pprint :refer [pprint]]
            [honeysql.format :as hsf]
            [honeysql.core :as hsc]
            [honeysql.helpers :refer :all]
            [toshtogo.util.core :refer [ppstr]]
            )
  (:import [java.sql Timestamp BatchUpdateException]
           (org.joda.time DateTime)
           (org.postgresql.util PSQLException)))

(defmulti fix-type-> class)
(defmethod fix-type-> DateTime [v] (to-timestamp v))
(defmethod fix-type-> :default [v] v)

(defmulti fix-type<- class)
(defmethod fix-type<- Timestamp [v] (from-sql-date v))
(defmethod fix-type<- :default [v] v)

(defn flatten-data [columns data]
  (->> data
       (map #(map (fn [column] (column %)) columns))
       (map #(map fix-type-> %))))

(defn query
      "sql-map is a honey-sql query map"
  [cnxn sql-map]
  (let [sql-params (map fix-type-> (hsf/format sql-map))]
    (->> (try
           (sql/query cnxn sql-params)
           (catch PSQLException e
             (throw (RuntimeException. (str "Could not execute" (ppstr sql-params)) e))))

         (map (fn [record] (mp/map-vals record fix-type<-))))))

(defn single
      "sql-map is a honey-sql query map"
  [cnxn sql-map]
  (first (query cnxn sql-map)))

(defn page
      [cnxn sql-map & {:keys [count-sql-map page page-size] :or {page 1 page-size 20}}]
  (let [record-count  (:cnt (single cnxn (-> (or count-sql-map sql-map)
                                             (select :%count.*)
                                             (dissoc :order-by)
                                             (hsc/build :offset (* page-size (- page 1))
                                                        :limit page-size)))
                       0)
        page-count    (ceil (/ record-count page-size))]
    {
      :paging {:page page :pages page-count}
      :data   (query cnxn sql-map)})
  )
