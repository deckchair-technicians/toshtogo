(ns toshtogo.test.util.deterministic-representation-test
  (:require [midje.sweet :refer :all]
            [toshtogo.util.deterministic-representation :refer :all]))

(fact "We can turn nested maps into deterministic representations with sorted keys"
      (deterministic-representation {:b "b"
                                     :a "a"
                                     :c {:A "A"
                                         :C "C"
                                         :B "B"}})
      => (sorted-map :a "a"
                     :b "b"
                     :c (sorted-map :A "A"
                                    :B "B"
                                    :C "C")))

(fact "deterministic-representation doesn't support keys that aren't keywords"
      (deterministic-representation {"some string" 3452345})
      => (throws IllegalArgumentException))

(fact "deterministic-representation doesn't support sets"
      (deterministic-representation #{})
      => (throws IllegalArgumentException))