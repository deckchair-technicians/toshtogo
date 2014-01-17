(ns toshtogo.test.jobs
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [toshtogo.web.middleware :refer [sql-deps]]
            [toshtogo.config :refer [dbs]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.agents :refer :all]
            [toshtogo.api :refer :all]))

(fact "Job records are the right shape"
  (let [agent-details (get-agent-details "test" "test")]
    (job-req ...id... agent-details {:data "value"} [:tag-one :tag-two])
    => {:job_id ...id...
        :agent agent-details
        :tags [:tag-one :tag-two]
        :request_body {:data "value"}}))

(fact "Adding a job with no dependencies triggers creation of a contract"
  (sql/with-db-transaction
   [cnxn (dbs :dev)]
   (let [id-one    (uuid)
         id-two    (uuid)
         tag-one   (uuid-str) ;so we can run against a dirty database
         tag-two   (uuid-str)
         {:keys [agents api]} (sql-deps cnxn)]

     (put-job! api (job-req id-one (get-agent-details "test" "0.0.0") {:some-data 123} [tag-one]))
     (put-job! api (job-req id-two  (get-agent-details "test" "0.0.0") {:some-data 456} [tag-two]))

     (get-contracts api {:state :waiting :tags [tag-one]})
     => (contains (contains {:job_id id-one})))))

(facts "Should be able to pause a job that hasn't started"
  (sql/with-db-transaction
   [cnxn (dbs :dev)]
   (let [job-id               (uuid)
         tag                  (uuid-str)
         agent                (get-agent-details "test" "0.0.0")
         {:keys [agents api]} (sql-deps cnxn)]

     (put-job! api (job-req job-id agent {:some-data 123} [tag]))
     (pause-job! api job-id)

     (get-contract api {:job_id job-id})
     => (contains {:outcome :cancelled})

     (request-work! api (uuid) [tag] agent)
     => nil)))

(facts "Should be able to pause a job that has started"
  (sql/with-db-transaction
    [cnxn (dbs :dev)]
    (let [job-id               (uuid)
          tag                  (uuid-str)
          agent                (get-agent-details "test" "0.0.0")
          commitment-id        (uuid)
          {:keys [agents api]} (sql-deps cnxn)]

      (put-job! api (job-req job-id agent {:some-data 123} [tag]))

      (request-work! api commitment-id [tag] agent) => truthy

      (pause-job! api job-id)

      (get-contract api {:job_id job-id})
      => (contains {:outcome :cancelled})

      (complete-work! api commitment-id (success {}))

      (get-contract api {:job_id job-id})
      => (contains {:outcome :cancelled}))))

    (future-fact "Should be able to pause a job with dependencies")

(future-fact "Get job returns job tags")
