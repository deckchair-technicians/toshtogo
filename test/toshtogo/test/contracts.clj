(ns toshtogo.test.contracts
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [toshtogo.web.middleware :refer [sql-deps]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.config :refer [dbs]]
            [toshtogo.agents :refer :all]
            [toshtogo.api :refer :all]))

(def agent-details (get-agent-details "test" "0.0.0"))

(defn given-job-exists [api id tags]
       (put-job! api (job-map id agent-details {:some-data (uuid)} tags)))

(defn given-job-succeeded [api job-id]
  (let [job      (get-job api job-id)
        contract (request-work! api (uuid) (job :tags) agent-details)]
    (when (not= (contract :job_id) job-id)
      (throw (RuntimeException. "More than one job exists for tags")))

    (complete-work! api (contract :commitment_id) (success {:response "success"}))))

(fact "Gets contracts by filters "
  (sql/db-transaction
   [cnxn (dbs :dev)]
   (let [id-one                         (uuid)
         id-two                         (uuid)
         tag-one                        (uuid-str) ;so we can run against a dirty database
         tag-two                        (uuid-str)
         {:keys [agents api]} (sql-deps cnxn)]

     (given-job-exists api id-two [tag-two])
     (given-job-exists api id-one [tag-one])

     (get-contracts api {:state :waiting :tags [tag-one]})
     => (contains (contains {:job_id id-one})))))

(facts "New contracts check for the state of old contracts"
  (sql/db-transaction
   [cnxn (dbs :dev)]
   (let [job-id    (uuid)
         tag   (uuid-str)        ;so we can run against a dirty database
         {:keys [agents api]} (sql-deps cnxn)]

     (given-job-exists api job-id [tag])

     (fact "Can't create contract when job is in progress"
       (new-contract! api (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has an unfinished contract. Can't create a new one.")))

     (fact "Can't create contract when job has succeeded"
       (given-job-succeeded api job-id)

       (new-contract! api (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has been completed. Can't create further contracts"))))))

(future-fact "Two agents requesting work at the same time will not collide")

(future-fact "If there is no work to do, no work is handed out")
