(ns toshtogo.test.sql-test
  (:import (java.sql Timestamp))
  (:require [midje.sweet :refer :all]
            [clojure.string :as str]
            [clj-time.core :refer [now date-time]]
           [toshtogo.util.sql :refer :all]))

(fact "Param pattern for keyword works"
  (str/replace ":some-param" (param-pattern-for :some-param) "?") => "?"
  (str/replace ":some_param" (param-pattern-for :some_param) "?") => "?"
  (str/replace ":someparam1" (param-pattern-for :someparam1) "?") => "?")

(fact "named-params works with no parameters"
  (named-params "select * from a_table" {}) => ["select * from a_table"])

(fact "regex to extract parameters works"
  (param-usages ":a :b-b :c_c :d0 :a")
  => (contains [:a :b-b :c_c :d0 :a]))

(fact "named-params replaces parameters"
  (named-params "select * from a_table where a = :a and b = :b and ALSO_A = :a" {:a 1 :b 2})
  => ["select * from a_table where a = ? and b = ? and ALSO_A = ?" 1 2 1])

(fact "named-params replaces in parameter params"
  (named-params "a = :a and b in (:b) and c in (:c) and a = :a" {:a 1 :b [2 3] :c '(4 5)})
  => ["a = ? and b in (?, ?) and c in (?, ?) and a = ?" 1 2 3 4 5 1])

(fact "named-params fixes datetimes"
  (let [d1 (date-time 2010 6 1 13 45 55 1)
        d2 (date-time 2015 8 4 0  45 55 1)]

    (named-params ":a :a" {:a d1})
    => ["? ?"
        (Timestamp. (.getMillis d1))
        (Timestamp. (.getMillis d1))]

    (named-params "in (:a)" {:a [d1 d2]})
    => ["in (?, ?)"
        (Timestamp. (.getMillis d1))
        (Timestamp. (.getMillis d2))]))

(fact "named-params fixes keyword parameter values"
  (named-params ":a :a" {:a :a-keyword})
  => ["? ?" "a-keyword" "a-keyword"])
