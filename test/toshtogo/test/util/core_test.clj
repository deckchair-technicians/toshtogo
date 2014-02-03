(ns toshtogo.test.util.core-test
  (:require [midje.sweet :refer :all]
            [toshtogo.util.core :refer :all]))

(defn attempt [succeed-on-attempt func]
  (let [a (atom 0)]
    (retry-until-success
      {}
      (if (< (swap! a inc) 5)
        (throw (RuntimeException. (str @a)))
        func))
    ))

(fact "retry-until-success"
      (attempt 5 ))
