(ns toshtogo.test.util.hashing-test
  (:import (java.io ByteArrayInputStream))
  (:require [midje.sweet :refer :all]
            [clojure.java.io :refer [input-stream]]
    [toshtogo.util.hashing :refer :all]))

(fact "We can hash an input stream"
      (murmur! (ByteArrayInputStream. (.getBytes "1234"))) => truthy
      (murmur! (ByteArrayInputStream. (.getBytes "1234"))) => #(not= (murmur! (ByteArrayInputStream. (.getBytes "12345"))) %)
      )

(fact "We can hash a map"
      (murmur! {:a 1}) => truthy
      (murmur! {:a 1}) => (murmur! {:a 1})
      (murmur! {:a 1}) => #(not= (murmur! {:a 2}) %)
      )

(fact "We can hash a vector"
      (murmur! [1 2 3]) => truthy
      (murmur! [1 2 3]) => (murmur! [1 2 3])
      (murmur! [1 2 3]) => #(not= (murmur! [1 2 3 4]) %)
      )

(fact "We can hash a sequence"
      (murmur! (range 1 4)) => truthy
      (murmur! (range 1 4)) => (murmur! (range 1 4))
      (murmur! (range 1 4)) => #(not= (murmur! (range 1 5)) %)
      )

(fact "We can hash a string"
      (murmur! "1234") => truthy
      (murmur! "1234") => (murmur! "1234")
      (murmur! "1234") => #(not= (murmur! "12345") %)
      )

(fact "Maps and sets have the same hash regardless of key order"
      (murmur! {:a "1234" :b #{5 6 7 8}}) => truthy
      (murmur! {:a "1234" :b  #{5 6 7 8}}) => (murmur! {:b  #{8 7 6 5} :a "1234"})
      (murmur! {:a "1234" :b  [5 6 7 8]}) => #(not= (murmur! {:a "1234"}) %)
      )