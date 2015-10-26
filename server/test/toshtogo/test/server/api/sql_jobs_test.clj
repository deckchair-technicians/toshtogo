(ns toshtogo.test.server.api.sql-jobs-test
  (:require [midje.sweet :refer :all]

            [clojure.java.jdbc :as sql]

            [toshtogo.test.server.api
             [util :refer [job-req]]]

            [toshtogo.test.functional
             [test-support :refer :all]]

            [toshtogo.server.persistence.protocol :refer :all]

            [toshtogo.server
             [api :refer :all]
             [core :refer [dev-db]]]

            [toshtogo.util
             [core :refer [uuid uuid-str]]]

            [toshtogo.client
             [protocol :refer [success add-dependencies]]]))

(background (before :contents @migrated-dev-db))

(defn complete-job! [api job-id result]
  (let [commitment-id (uuid)]
    (request-work! api commitment-id {:job_id job-id})
    => truthy
    (complete-work! api commitment-id result)))

(fact "Job records are the right shape"
      (job-req ...id... {:data "value"} :job-type)
      => {:job_id       ...id...
          :job_type         :job-type
          :request_body {:data "value"}})

(fact "Adding a job triggers creation of a contract"
      (sql/with-db-transaction
        [cnxn dev-db]
        (let [id-one (uuid)
              id-two (uuid)
              job-type-one (uuid-str) ;so we can run against a dirty database
              job-type-two (uuid-str)
              {:keys [api persistence]} (deps cnxn)]

          (new-root-job! api (job-req id-one {:some-data 123} job-type-one))
          (new-root-job! api (job-req id-two {:some-data 456} job-type-two))

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
              {:keys [api persistence]} (deps cnxn)]

          (new-root-job! api (job-req parent-id {:some-data 123} parent-type))

          (complete-job! api parent-id (add-dependencies (job-req child-id {:some-data 456} child-type)))

          (fact "Parent job is initially not ready"
                (request-work! api (uuid) {:job_type parent-type})
                => nil)

          (fact "Child job is requested successfully"
                (request-work! api child-commitment-id {:job_type child-type})
                => (contains {:job_id child-id}))

          (fact "Child job completes successfully"
                (complete-work! api child-commitment-id (success {}))

                (get-contract persistence {:commitment_id child-commitment-id})
                => (contains {:outcome :success}))

          (fact "Parent job is ready when child job is finished"
                (request-work! api (uuid) {:job_type parent-type})
                => (contains {:job_id parent-id})))))

(facts "Should be able to pause a job that hasn't started"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               job-type (uuid-str)
               {:keys [api persistence]} (deps cnxn)]

           (new-root-job! api (job-req job-id {:some-data 123} job-type))

           (get-job persistence job-id)
           => (contains {:outcome :waiting})

           (pause-job! api job-id)

           (get-contract persistence {:job_id job-id})
           => (contains {:outcome :cancelled})

           (request-work! api (uuid) {:job_type job-type})
           => nil)))

(facts "Should be able to pause a job that has started"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               job-type (uuid-str)
               commitment-id (uuid)
               {:keys [api persistence]} (deps cnxn)]

           (new-root-job! api (job-req job-id {:some-data 123} job-type))

           (request-work! api commitment-id {:job_type job-type}) => truthy
           (get-job persistence job-id)
           => (contains {:outcome :running})

           (pause-job! api job-id)

           (get-contract persistence {:job_id job-id})
           => (contains {:outcome :cancelled})

           (complete-work! api commitment-id (success {}))

           (get-contract persistence {:job_id job-id})
           => (contains {:outcome :cancelled}))))

(facts "Should be able to pause a job that has finished"
       (sql/with-db-transaction
         [cnxn dev-db]
         (let [job-id (uuid)
               job-type (uuid-str)
               commitment-id (uuid)
               {:keys [api persistence]} (deps cnxn)]

           (new-root-job! api (job-req job-id {:some-data 123} job-type))

           (request-work! api commitment-id {:job_type job-type}) => truthy
           (complete-work! api commitment-id (success {}))

           (pause-job! api job-id)

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
               {:keys [api persistence]} (deps cnxn)]

           (new-root-job! api
                     (job-req job-id-1 {} job-type
                              :dependencies
                              [(job-req job-id-1-1 {} job-type)
                               (job-req job-id-1-2 {} job-type
                                        :dependencies [(job-req job-id-1-2-1 {} job-type)])]))
           (pause-job! api job-id-1)

           (get-contract persistence {:job_id job-id-1-2-1})
           => (contains {:outcome :cancelled})

           (request-work! api (uuid) {:job_type job-type}) => nil)))
