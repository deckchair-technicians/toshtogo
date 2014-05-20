(ns toshtogo.test.server.api.persistence.jobs-helper-test
  (:require [midje.sweet :refer :all]
            [toshtogo.server.persistence.sql-jobs-helper :refer :all]))

(fact "Folding in tags works"
      (fold-in-tags [{:tag "a"} {:tag "b"}])
      => (contains {:tags (contains ["a" "b"]
                                    :in-any-order)})

      (fold-in-tags [{:tag "a"}])
      => {:tags ["a"]}

      (fold-in-tags [{:tag nil}])
      => {:tags #{}}

      (fold-in-tags [{}])
      => {}

      )
