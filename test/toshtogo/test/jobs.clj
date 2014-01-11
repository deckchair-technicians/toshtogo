(ns toshtogo.test.jobs
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [toshtogo.config :refer [dbs]]
            [toshtogo.util :refer [uuid uuid-str debug]]
            [toshtogo.agents :refer :all]
            [toshtogo.jobs :refer :all]
            [toshtogo.contracts :refer :all]))

(fact "Job records are the right shape"
  (let [agent-details (get-agent-details "test" "test")]
    (job ...id... agent-details {:data "value"} [:tag-one :tag-two])
    => {:job_id ...id...
        :agent agent-details
        :tags [:tag-one :tag-two]
        :body {:data "value"}}))

(fact "Adding a job with no dependencies triggers creation of a contract"
  (sql/db-transaction
   [cnxn (dbs :dev)]
   (let [id-one    (uuid)
         id-two    (uuid)
         tag-one   (uuid-str) ;so we can run against a dirty database
         tag-two   (uuid-str)
         agents    (sql-agents cnxn)
         contracts (sql-contracts cnxn agents)
         jobs      (sql-jobs cnxn agents contracts)]

     (put-job! jobs (job id-one (get-agent-details "test" "0.0.0") {:some-data 123} [tag-one]))
     (put-job! jobs (job id-two  (get-agent-details "test" "0.0.0") {:some-data 456} [tag-two]))

     (get-contracts contracts {:state :waiting :tags [tag-one]})
     => (contains (contains {:job_id id-one})))))
