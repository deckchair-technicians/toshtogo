(ns toshtogo.util.hsql
  (:require [clj-time.coerce :refer [to-timestamp from-sql-date]]
            [clojure.java.jdbc :as sql]
            [clojure.math.numeric-tower :refer [ceil]]
            [flatland.useful.map :as mp]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [postwalk]]
            [honeysql.format :as hsf]
            [honeysql.core :as hsc]
            [honeysql.helpers :refer :all]
            [toshtogo.util.core :refer [ppstr]]
            )
  (:import [java.sql Timestamp BatchUpdateException]
           (org.joda.time DateTime)
           (org.postgresql.util PSQLException)
           (clojure.lang Keyword)))


(defmulti fix-type-> class)
(defmethod fix-type-> DateTime [v] (to-timestamp v))
(defmethod fix-type-> Keyword [v] (name v))
(defmethod fix-type-> :default [v] v)

(defmulti fix-type<- class)
(defmethod fix-type<- Timestamp [v] (from-sql-date v))
(defmethod fix-type<- :default [v] v)

(defn flatten-data [columns data]
  (->> data
       (map #(map (fn [column] (column %)) columns))
       (map #(map fix-type-> %))))

(defn- query* [cnxn sql-params]
  (try
    (sql/query cnxn sql-params)
    (catch Throwable e
      (throw (Exception. (str "Could not execute" (ppstr sql-params)) e)))))

(defn query
      "sql-map is a honey-sql query map"
  [cnxn sql-map & {:keys [params]}]
  (let [sql-params (map fix-type-> (hsf/format sql-map :params params))]
    (->> (query* cnxn sql-params)
         (map (fn [record] (mp/map-vals record fix-type<-))))))

(defn single
      "sql-map is a honey-sql query map"
  [cnxn sql-map & {:keys [params]}]
  (first (query cnxn sql-map :params params)))

(defn page
      [cnxn sql-map & {:keys [page page-size count-sql-map params count-params]}]
  (let [page (or page 1)
        page-size (or page-size 25)
        record-count  (:cnt (single cnxn (-> (or count-sql-map sql-map)
                                             (select [:%count.* :cnt])
                                             (dissoc :order-by))
                                    :params (or count-params params))
                       0)
        page-count    (ceil (/ record-count page-size))]
    {:paging {:page page :pages page-count}
      :data   (query cnxn (-> sql-map
                              (hsc/build :offset (* page-size (- page 1))
                                         :limit page-size))
                     :params params)}))

(defmulti prefix-alias-columns (fn [table-aliases x] (class x)))
(defmethod prefix-alias-columns Keyword [table-aliases x]
  (let [[_ table-name rest] (re-find #"(.*)(\..*)" (name x))
        table-keyword     (keyword table-name)
        table-alias       (table-aliases table-keyword)]
    (if (and table-alias)
      (keyword (str (name table-alias) rest))
      x)))

(defmethod prefix-alias-columns :default [table-aliases x] (identity x))

(defn table-alias-pair [table-aliases table-keyword]
  [table-keyword (or (table-aliases table-keyword) table-keyword)])

(defn prefix-alias-tables [q prefix]
  (let [table-aliases (->> (:left-join q)
                           (apply hash-map)
                           keys
                           (concat (:from q))
                           (mapcat (fn [table-name] [table-name (keyword (str prefix (name table-name)))]))
                           (apply hash-map))]
    (-> (postwalk (partial prefix-alias-columns table-aliases) q)
        (mp/update :left-join #(partition 2 %))
        (mp/update :left-join #(map (fn [[table-keyword join-expression]]
                                   [(table-alias-pair table-aliases table-keyword)
                                    join-expression])
                                 %))
        (mp/update :left-join #(mapcat identity %))
        (mp/update :from #(map (partial table-alias-pair table-aliases) %)))))