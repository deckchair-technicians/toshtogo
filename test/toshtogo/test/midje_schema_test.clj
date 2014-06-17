(ns toshtogo.test.midje-schema-test
  (:require [toshtogo.test.midje-schema :refer :all]
            [midje.sweet :refer :all]
            [schema.macros :as mac]
            [schema.utils :as utils]
            [clojure.walk :refer [postwalk]]
            [clojure.pprint :refer [pprint]])
  (:import [schema.utils ValidationError]))

(defn errors [schema x]
  (postwalk (fn [e]
              (if (instance? ValidationError e)
                (utils/validation-error-explain e)
                e)
              )
            (check schema x)))

(fact "in-any-order: too many elements"
      (errors {:a (in-any-order [2 1])}
              {:a [3 1 2 4]})
      => {:a ['(not (present? 3))
              '(not (has-extra-elts? (4)))]})

(fact "in-any-order: too many matchers"
      (errors {:a (in-any-order [2 3 1])}
              {:a [1 2]})
      => {:a ['(not (missing-items? 3))]})

(fact "in-any-order: match"
      (errors {:a (in-any-order [2 1])}
              {:a [1 2]})
      => nil)

(fact "in-any-order: no match"
      (errors {:a (in-any-order [3 1])}
              {:a [1 2]})
      => {:a ['(not (present? 2))
              '(not (missing-items? 3))]})

(fact "in-any-order: nested maps"
      (errors
        {:a (in-any-order [{:b 1} {:b 2}])}
        {:a [{:b 1} {:b 2}]})
      => nil)

(fact "in-order: too many elements"
      (errors {:a (in-order [1 2])}
              {:a [1 2 3]})
      => {:a ['(not (has-extra-elts? (3)))]})

(fact "in-order: too many matchers"
      (errors {:a (in-order [1 2 3 4])}
              {:a [1 2]})
      => {:a ['(not (missing-items? 3 4))]})

(fact "in-order: match"
      (errors {:a (in-order [1 2])}
              {:a [1 2]})
      => nil)

(fact "in-order: wrong order"
      (errors {:a (in-order [1 2])}
              {:a [2 1]})
      => {:a ['(not 1)
              '(not 2)]})

(fact "in-order: nested maps"
      (errors
        {:a (in-order [{:b 1} {:b 2}])}
        {:a [{:b 1} {:b 2}]})
      => nil)