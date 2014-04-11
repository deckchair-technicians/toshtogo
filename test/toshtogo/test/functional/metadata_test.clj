(ns toshtogo.test.functional.metadata-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [toshtogo.server.util.middleware :refer [sql-deps]]
            [toshtogo.server.core :refer [dev-db]]
            [toshtogo.util.core :refer [uuid-str uuid]]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.server.core :refer [dev-db]]))

(background (before :contents @migrated-dev-db))

(fact "Can get a list of available job types"
      (sql/execute! dev-db ["truncate jobs cascade"])
      (let [job-type-1 (keyword (uuid-str))
            job-type-2 (keyword (uuid-str))]
        (put-job! client (uuid) (job-req {} job-type-1))
        (put-job! client (uuid) (job-req {} job-type-2))
        (get-job-types client)
        => (contains [job-type-1 job-type-2] :in-any-order)))
