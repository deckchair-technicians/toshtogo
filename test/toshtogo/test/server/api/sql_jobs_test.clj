(ns toshtogo.test.server.api.sql-jobs-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [clojure.pprint :refer [pprint]]
            [toshtogo.test.server.api.util :refer [job-req]]
            [toshtogo.server.util.middleware :refer [sql-deps]]
            [toshtogo.server.core :refer [dev-db]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.client.util :as util]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.api :refer :all]))

(background (before :contents @migrated-dev-db))

(def agent-details (util/agent-details "savagematt" "toshtogo"))

(defn complete-job! [persistence job-id response]
  (let [commitment-id (uuid)]
    (request-work! persistence commitment-id {:job_id job-id} agent-details)
    => truthy
    (complete-work! persistence commitment-id response agent-details)))

(fact "Job records are the right shape"
      (job-req ...id... {:data "value"} :job-type :tags [:tag-one :tag-two])
      => {:job_id       ...id...
          :job_type         :job-type
          :tags         [:tag-one :tag-two]
          :request_body {:data "value"}})

(fact "Adding a job triggers creation of a contract"
      (sql/with-db-transaction
        [cnxn dev-db]
        (let [id-one (uuid)
              id-two (uuid)
              job-type-one (uuid-str) ;so we can run against a dirty database
              job-type-two (uuid-str)
              {:keys [_ persistence]} (sql-deps cnxn)]

          (new-root-job! persistence agent-details (job-req id-one {:some-data 123} job-type-one))
          (new-root-job! persistence agent-details (job-req id-two {:some-data 456} job-type-two))

          (get-contracts persistence {:outcome :waiting :job_type job-type-one})
          => (contains (contains {:job_id id-one})))))

(fact "Completing a dependency makes the parent job available for work"
      (sql/with-db-transaction
        [cnxn dev-db]
        (let [parent-id (uuid)
              child-id (uuid)
              parent-type (uuid-str) ;so we can run against a dirty database
              child-type (uuid-str)
              child-commitment-id (uuid)
              {:keys [_ persistence]} (sql-deps cnxn)]

          (new-root-job! persistence agent-details (job-req parent-id {:some-data 123} parent-type))

          (complete-job! persistence parent-id (add-dependencies (job-req child-id {:some-data 456} child-type)))

          (fact "Parent job is initially not ready"
                (request-work! persistence (uuid) {:job_type parent-type} agent-details)
                => nil)

          (fact "Child job is requested successfully"
                (request-work! persistence child-commitment-id {:job_type child-type} agent-details)
                => (contains {:job_id child-id}))

          (fact "Child job completes successfully"
                (complete-work! persistence child-commitment-id (success {}) agent-details)

                (get-contract persistence {:commitment_id child-commitment-id})
                => (contains {:outcome :success}))

          (fact "Parent job is ready when child job is finished"
                (request-work! persistence (uuid) {:job_type parent-type} agent-details)
                => (contains {:job_id parent-id})))))

(facts "Should be able to pause a job that hasn't started"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               job-type (uuid-str)
               {:keys [_ persistence]} (sql-deps cnxn)]

           (new-root-job! persistence agent-details (job-req job-id {:some-data 123} job-type))

           (get-job persistence job-id)
           => (contains {:outcome :waiting})

           (pause-job! persistence job-id agent-details)

           (get-contract persistence {:job_id job-id})
           => (contains {:outcome :cancelled})

           (request-work! persistence (uuid) {:job_type job-type} agent-details)
           => nil)))

(facts "Should be able to pause a job that has started"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               job-type (uuid-str)
               commitment-id (uuid)
               {:keys [_ persistence]} (sql-deps cnxn)]

           (new-root-job! persistence agent-details (job-req job-id {:some-data 123} job-type))

           (request-work! persistence commitment-id {:job_type job-type} agent-details) => truthy
           (get-job persistence job-id)
           => (contains {:outcome :running})

           (pause-job! persistence job-id agent-details)

           (get-contract persistence {:job_id job-id})
           => (contains {:outcome :cancelled})

           (complete-work! persistence commitment-id (success {}) agent-details)

           (get-contract persistence {:job_id job-id})
           => (contains {:outcome :cancelled}))))

(facts "Should be able to pause a job that has finished"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               job-type (uuid-str)
               commitment-id (uuid)
               {:keys [persistence]} (sql-deps cnxn)]

           (new-root-job! persistence agent-details (job-req job-id {:some-data 123} job-type))

           (request-work! persistence commitment-id {:job_type job-type} agent-details) => truthy
           (complete-work! persistence commitment-id (success {}) agent-details)

           (pause-job! persistence job-id agent-details)

           (get-contract persistence {:job_id job-id})
           => (contains {:outcome :success}))))

(facts "Should be able to pause a job with dependencies"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id-1 (uuid)
               job-id-1-1 (uuid)
               job-id-1-2 (uuid)
               job-id-1-2-1 (uuid)
               job-type (uuid-str)
               {:keys [_ persistence]} (sql-deps cnxn)]

           (new-root-job! persistence
                     agent-details
                     (job-req job-id-1 {} job-type
                              :dependencies
                              [(job-req job-id-1-1 {} job-type)
                               (job-req job-id-1-2 {} job-type
                                        :dependencies [(job-req job-id-1-2-1 {} job-type)])]))
           (pause-job! persistence job-id-1 agent-details)

           (get-contract persistence {:job_id job-id-1-2-1})
           => (contains {:outcome :cancelled})

           (request-work! persistence (uuid) {:job_type job-type} agent-details) => nil)))
