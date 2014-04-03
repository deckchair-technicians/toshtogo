(ns toshtogo.util.sql
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [clojure.math.numeric-tower :refer [ceil]]
            [flatland.useful.map :refer [map-vals filter-vals update update-each map-keys]]
            [clojure.set :refer [difference]]
            [clojure.walk :refer [postwalk]]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [toshtogo.util.core :refer :all])
  (:import [java.sql PreparedStatement BatchUpdateException Timestamp]
           [clojure.lang Keyword]
           [java.lang IllegalArgumentException]
           (org.joda.time DateTime)))

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

(defn param-usages [sql]
  (map keyword (map second (re-seq param-pattern sql))))

(defn param-pattern-for
  [param-keyword]
  (re-pattern (str ":" (name param-keyword))))

(defn add-param [sql params])

(def is-in-clause-param? (some-fn vector? list? seq?))

(defn replace-in-clause-param
  [sql entry]
  (let [k              (.getKey entry)
        values         (.getValue entry)
        pattern        (param-pattern-for k)
        question-marks (str/join ", " (repeat (count values) "?"))]
    (when (empty? values)
      (throw (IllegalArgumentException. (str k " was empty- should not add parameters"))))
    (str/replace sql pattern question-marks)))

(defmulti fix-type class)
(defmethod fix-type DateTime [v] (Timestamp. (.getMillis v)))
(defmethod fix-type Keyword [v] (name v))
(defmethod fix-type :default [v] (identity v))

(defn add-param-value [params values k]
  (let [value (params k)]
    (if (is-in-clause-param? value)
      (concat values (map fix-type value))
      (concat values [(fix-type value)]))))

(defn named-params
  [sql params]
  (let [keywords-usage-order (param-usages sql)]
    (assert-keys params (set keywords-usage-order))

    (let [in-clause-params            (filter-vals (dissoc params :order-by) is-in-clause-param?)
          sql-with-in-params-replaced (reduce replace-in-clause-param sql in-clause-params)
          sql                         (str/replace sql-with-in-params-replaced param-pattern "?")]

      (concat [sql]
              (reduce (partial add-param-value params) (vector) keywords-usage-order)))))

(defn qualify [where-clauses-fn sql params]
  (no-debug "Qualify:" [sql params])
  (let [[out-params where-clauses] (where-clauses-fn params)]
    [(cond-> sql
             (not-empty where-clauses)
             (str "\n    where\n      "    (str/join "\n      and " where-clauses))

             (:order-by params)
             (str "\n    order by " (str/join " desc nulls last, " (map name (:order-by params))))

             (or (:page params) (:page-size params))
             (str "\n    offset " (* (:page-size params 20) (- (:page params 1) 1))
                  "\n    limit " (:page-size params 20))

             (:get-count params)
             (str/replace #"^\s*select\s*.*\s*from\s*" "select count(*) as cnt from "))
     out-params]))

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

(defn query
  "Takes some sql including references to parameters in the form
   :parameter-name and a map of named parameters"
  ([cnxn sql-params]
   (query cnxn (first sql-params) (second sql-params)))
  ([cnxn sql params]
   (let [fixed-params (named-params sql params)]
     (try
       (sql/query cnxn (no-debug "QUERY" fixed-params))
       (catch Throwable e
         (throw (RuntimeException. (str "Problem executing " (ppstr fixed-params)) )))))))

(defn query-single
  ([cnxn sql-params]
   (query-single cnxn (first sql-params) (second sql-params)))
  ([cnxn sql params]
   (first (query cnxn sql params))))

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

(defn prefix-alias-tables [prefix q]
  (let [table-aliases (->> (:left-join q)
                         (apply hash-map)
                         keys
                         (concat (:from q))
                         (mapcat (fn [table-name] [table-name (keyword (str prefix (name table-name)))]))
                         (apply hash-map))]
    (-> (postwalk (partial prefix-alias-columns table-aliases) q)
        (update :left-join #(partition 2 %))
        (update :left-join #(map (fn [[table-keyword join-expression]]
                                        [(table-alias-pair table-aliases table-keyword)
                                         join-expression])
                                 %))
        (update :left-join #(mapcat identity %))
        (update :from #(map (partial table-alias-pair table-aliases) %)))))