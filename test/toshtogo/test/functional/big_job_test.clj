(ns toshtogo.test.functional.big-job-test
  (:require [midje.sweet :refer :all]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str]]
            [toshtogo.test.functional.test-support :refer :all]))

(background (before :contents @migrated-dev-db))

(defn big-request []
  {:data (take 1000 (repeatedly #(uuid-str)))})

(future-fact
  "We can handle large request bodies (current fails because the text is too big for Postgres to index)"
  (put-job! client (uuid) (job-req (big-request) (uuid-str))))
