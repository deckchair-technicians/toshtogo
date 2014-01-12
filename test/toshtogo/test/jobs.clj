(ns toshtogo.test.jobs
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [toshtogo.middleware :refer [sql-deps]]
            [toshtogo.config :refer [dbs]]
            [toshtogo.util :refer [uuid uuid-str debug]]
            [toshtogo.agents :refer :all]
            [toshtogo.jobs :refer :all]
            [toshtogo.contracts :refer :all]
            [toshtogo.contracts.sql :refer [sql-contracts]]))

(fact "Job records are the right shape"
  (let [agent-details (get-agent-details "test" "test")]
    (job-map ...id... agent-details {:data "value"} [:tag-one :tag-two])
    => {:job_id ...id...
        :agent agent-details
        :tags [:tag-one :tag-two]
        :request_body {:data "value"}}))

(fact "Adding a job with no dependencies triggers creation of a contract"
  (sql/db-transaction
   [cnxn (dbs :dev)]
   (let [id-one    (uuid)
         id-two    (uuid)
         tag-one   (uuid-str) ;so we can run against a dirty database
         tag-two   (uuid-str)
         {:keys [agents contracts jobs]} (sql-deps cnxn)]

     (put-job! jobs (job-map id-one (get-agent-details "test" "0.0.0") {:some-data 123} [tag-one]))
     (put-job! jobs (job-map id-two  (get-agent-details "test" "0.0.0") {:some-data 456} [tag-two]))

     (get-contracts contracts {:state :waiting :tags [tag-one]})
     => (contains (contains {:job_id id-one})))))

(future-fact "Get job returns job tags")
