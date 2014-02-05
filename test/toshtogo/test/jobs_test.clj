(ns toshtogo.test.jobs-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [toshtogo.server.util.middleware :refer [sql-deps]]
            [toshtogo.server.core :refer [dev-db]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.client.util :refer [get-agent-details]]
            [toshtogo.server.agents.protocol :refer :all]
            [toshtogo.server.api.protocol :refer :all]))

(def agent-details (get-agent-details "test" "0.0.0"))

(fact "Job records are the right shape"
      (job-req ...id... agent-details {:data "value"} [:tag-one :tag-two])
      => {:job_id       ...id...
          :agent        agent-details
          :tags         [:tag-one :tag-two]
          :request_body {:data "value"}})

(fact "Adding a job with no dependencies triggers creation of a contract"
      (sql/with-db-transaction
        [cnxn dev-db]
        (let [id-one (uuid)
              id-two (uuid)
              tag-one (uuid-str) ;so we can run against a dirty database
              tag-two (uuid-str)
              {:keys [agents api]} (sql-deps cnxn)]

          (put-job! api (job-req id-one agent-details {:some-data 123} [tag-one]))
          (put-job! api (job-req id-two agent-details {:some-data 456} [tag-two]))

          (get-contracts api {:state :waiting :tags [tag-one]})
          => (contains (contains {:job_id id-one})))))

(facts "Should be able to pause a job that hasn't started"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               tag (uuid-str)
               {:keys [agents api]} (sql-deps cnxn)]

           (put-job! api (job-req job-id agent-details {:some-data 123} [tag]))
           (pause-job! api job-id agent-details)

           (get-contract api {:job_id job-id})
           => (contains {:outcome :cancelled})

           (request-work! api (uuid) [tag] agent-details)
           => nil)))

(facts "Should be able to pause a job that has started"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               tag (uuid-str)
               commitment-id (uuid)
               {:keys [agents api]} (sql-deps cnxn)]

           (put-job! api (job-req job-id agent-details {:some-data 123} [tag]))

           (request-work! api commitment-id [tag] agent-details) => truthy

           (pause-job! api job-id agent-details)

           (get-contract api {:job_id job-id})
           => (contains {:outcome :cancelled})

           (complete-work! api commitment-id (success {}))

           (get-contract api {:job_id job-id})
           => (contains {:outcome :cancelled}))))

(facts "Should be able to pause a job that has finished"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               tag (uuid-str)
               commitment-id (uuid)
               {:keys [agents api]} (sql-deps cnxn)]

           (put-job! api (job-req job-id agent-details {:some-data 123} [tag]))

           (request-work! api commitment-id [tag] agent-details) => truthy
           (complete-work! api commitment-id (success {}))

           (pause-job! api job-id agent-details)

           (get-contract api {:job_id job-id})
           => (contains {:outcome :success}))))

(facts "Should be able to pause a job with dependencies"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id-1 (uuid)
               job-id-1-1 (uuid)
               job-id-1-2 (uuid)
               job-id-1-2-1 (uuid)
               tag (uuid-str)
               commitment-id (uuid)
               {:keys [agents api]} (sql-deps cnxn)]

           (put-job! api (job-req job-id-1 agent-details {} [tag]
                                  (job-req job-id-1-1 agent-details {} [tag])
                                  (job-req job-id-1-2 agent-details {} [tag]
                                           (job-req job-id-1-2-1 agent-details {} [tag]))))

           (pause-job! api job-id-1 agent-details)

           (get-contract api {:job_id job-id-1-2-1})
           => (contains {:outcome :cancelled})

           (request-work! api (uuid) [tag] agent-details) => nil)))

(future-fact "Get job returns job tags")
