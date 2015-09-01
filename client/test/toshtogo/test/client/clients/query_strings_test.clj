(ns toshtogo.test.client.clients.query-strings-test
  (:require [midje.sweet :refer :all]
            [ring.util.codec :refer [form-decode]]
            [ring.middleware.keyword-params :refer [keyword-params-request]]
            [toshtogo.client.clients.sender-client :refer [to-query-string]]
            [toshtogo.client.util :refer [normalise-search-params]]))

(defn keywordify [params]
  (-> {:params params}
    keyword-params-request
    :params))

(defn to-query-string-and-back [query]
  (-> query
      to-query-string
      form-decode
      keywordify
      normalise-search-params))

(fact "Converting order-by into query string works"
      (to-query-string-and-back {:order-by [:abc]})
      => (contains {:order-by [[:abc :asc]]})

      (to-query-string-and-back {:order-by [[:abc :desc]]})
      => (contains {:order-by [[:abc :desc]]})

      (to-query-string-and-back {:order-by [[:abc :desc] :def]})
      => (contains {:order-by [[:abc :desc] [:def :asc]]}))

(fact "Converting job_type into query string works"
      (to-query-string-and-back {:job_type :abc})
      => (contains {:job_type [:abc]}))

(fact "Converting tags into query string works"
      (to-query-string-and-back {:tags [:abc :def]})
      => (contains {:tags [:abc :def]}))
