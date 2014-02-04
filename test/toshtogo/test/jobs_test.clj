(ns toshtogo.test.jobs-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [toshtogo.web.middleware :refer [sql-deps]]
            [toshtogo.core :refer [dev-db]]
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
   [cnxn dev-db]
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
   [cnxn dev-db]
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
    [cnxn dev-db]
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

(facts "Should be able to pause a job that has finished"
  (sql/with-db-transaction
    [cnxn dev-db]
    (let [job-id               (uuid)
          tag                  (uuid-str)
          agent                (get-agent-details "test" "0.0.0")
          commitment-id        (uuid)
          {:keys [agents api]} (sql-deps cnxn)]

      (put-job! api (job-req job-id agent {:some-data 123} [tag]))

      (request-work! api commitment-id [tag] agent) => truthy
      (complete-work! api commitment-id (success {}))

      (pause-job! api job-id)

      (get-contract api {:job_id job-id})
      => (contains {:outcome :success}))))

(facts "Should be able to pause a job with dependencies"
  (sql/with-db-transaction
    [cnxn dev-db]
    (let [job-id-1             (uuid)
          job-id-1-1           (uuid)
          job-id-1-2           (uuid)
          job-id-1-2-1         (uuid)
          tag                  (uuid-str)
          agent                (get-agent-details "test" "0.0.0")
          commitment-id        (uuid)
          {:keys [agents api]} (sql-deps cnxn)]

      (put-job! api (job-req job-id-1 agent {} [tag]
                             (job-req job-id-1-1 agent {} [tag])
                             (job-req job-id-1-2 agent {} [tag]
                                      (job-req job-id-1-2-1 agent {} [tag]))))

      (pause-job! api job-id-1)

      (get-contract api {:job_id job-id-1-2-1})
      => (contains {:outcome :cancelled})

      (request-work! api (uuid) [tag] agent) => nil)))

(future-fact "Get job returns job tags")
