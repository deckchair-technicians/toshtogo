(ns toshtogo.test.server.api.sql-contracts-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.server.api.util :refer [job-req]]
            [toshtogo.server.core :refer [dev-db]]
            [toshtogo.client
             [protocol :refer [success]]
             [util :as util]]
            [toshtogo.server.persistence.protocol :refer [contract-req get-job get-contracts ]]
            [toshtogo.server.api :refer :all]
            [toshtogo.test.functional.test-support :refer :all]))

(background (before :contents @migrated-dev-db))

(defn given-job-exists [api id job-type & deps]
  (new-root-job! api (job-req id {:some-data (uuid)} job-type :dependencies deps)))

(defn given-job-succeeded [api job-id]
  (let [job      (get-job api job-id)
        contract (request-work! api (uuid) {:job_id job-id})]
    (complete-work! api (contract :commitment_id) (success {:response "success"}))))

(fact "Gets contracts by filters "
  (sql/with-db-transaction
   [cnxn dev-db]
   (let [id-one                         (uuid)
         id-two                         (uuid)
         job-type-one                   (uuid-str) ;so we can run against a dirty database
         job-type-two                   (uuid-str)
         {:keys [api persistence]} (deps cnxn)]

     (given-job-exists api id-two job-type-two)
     (given-job-exists api id-one job-type-one)

     (get-contracts persistence {:outcome :waiting :job_type job-type-one})
     => (contains (contains {:job_id id-one})))))

(facts "New contracts check for the state of old contracts"
  (sql/with-db-transaction
   [cnxn dev-db]
   (let [job-id    (uuid)
         job-type   (uuid-str)        ;so we can run against a dirty database
         commitment-id (uuid)
         {:keys [api]} (deps cnxn)]

     (given-job-exists api job-id job-type)

     (fact "Can't create contract when job is waiting"
       (new-contract! api (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has a waiting contract. Can't create a new one.")))

     (fact "Can't create contract when job is running"
       (request-work! api commitment-id {:job_id job-id})
       => truthy

       (new-contract! api (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has a running contract. Can't create a new one.")))

     (fact "Can't create contract when job has succeeded"
        (complete-work! api commitment-id (success {:response "success"}))

       (new-contract! api (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has been completed. Can't create further contracts"))))))
