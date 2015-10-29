(ns toshtogo.test.client.clients.query-strings-test
  (:require [midje.sweet :refer :all]
            [toshtogo.client.clients.sender-client :refer [to-query-string]]
            [toshtogo.client.util :refer [normalise-search-params]]))

(fact "Converting order-by into query string works"
  (to-query-string {:order-by [:abc]})
  => "order-by=abc"

  (to-query-string {:order-by [[:abc :desc]]})
  => "order-by=abc+desc"

  (to-query-string {:order-by [[:abc :desc] :def]})
  => "order-by=abc+desc&order-by=def")

(fact "Converting job_type into query string works"
  (to-query-string {:job_type :abc})
  => "job_type=abc")