(ns toshtogo.test.server.api.sql-contracts-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [toshtogo.server.util.middleware :refer [sql-deps]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.server.api.util :refer [job-req]]
            [toshtogo.server.core :refer [dev-db]]
            [toshtogo.client.util :as util]
            [toshtogo.server.agents.protocol :refer :all]
            [toshtogo.server.persistence.protocol :refer :all]))

(def agent-details (util/agent-details "savagematt" "toshtogo"))

(defn given-job-exists [api id job-type & deps]
  (new-job! api agent-details (job-req id {:some-data (uuid)} job-type :dependencies deps)))

(defn given-job-succeeded [api job-id]
  (let [job      (get-job api job-id)
        contract (request-work! api (uuid) {:job_type (job :job_type)} agent-details)]
    (when (not= (contract :job_id) job-id)
      (throw (RuntimeException. "More than one job exists for tags")))

    (complete-work! api (contract :commitment_id) (success {:response "success"}))))

(fact "Gets contracts by filters "
  (sql/with-db-transaction
   [cnxn dev-db]
   (let [id-one                         (uuid)
         id-two                         (uuid)
         job-type-one                        (uuid-str) ;so we can run against a dirty database
         job-type-two                        (uuid-str)
         {:keys [agents api]} (sql-deps cnxn)]

     (given-job-exists api id-two job-type-two)
     (given-job-exists api id-one job-type-one)

     (get-contracts api {:state :waiting :job_type [job-type-one]})
     => (contains (contains {:job_id id-one})))))

(facts "New contracts check for the state of old contracts"
  (sql/with-db-transaction
   [cnxn dev-db]
   (let [job-id    (uuid)
         job-type   (uuid-str)        ;so we can run against a dirty database
         {:keys [agents api]} (sql-deps cnxn)]

     (given-job-exists api job-id job-type)

     (fact "Can't create contract when job is in progress"
       (new-contract! api (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has an unfinished contract. Can't create a new one.")))

     (fact "Can't create contract when job has succeeded"
       (given-job-succeeded api job-id)

       (new-contract! api (contract-req job-id))
       => (throws IllegalStateException
                  (str "Job " job-id " has been completed. Can't create further contracts"))))))

(defn in-trans [tag db f entered start ended commit committed]
  (let [[result exception]
        (sql/with-db-transaction
          [cnxn db :isolation :read-uncommitted]

          (let [{:keys [api]} (sql-deps cnxn)]
            (deliver entered nil)
            @start
            (try
              (let [result (f api)]
                (deliver ended nil)
                @commit
                [result nil])
              (catch Throwable e
                (do
                  (deliver ended nil)
                  @commit
                  [nil e])))))]
    (deliver committed nil)
    (if result
      result
      exception)))

(defn in-parallel-transactions [db first second]
  (let [entered-1   (promise)
        entered-2   (promise)
        ended-1     (promise)
        ended-2     (promise)
        start-1     (promise)
        start-2     (promise)
        commit-1     (promise)
        commit-2     (promise)
        committed-1 (promise)
        committed-2 (promise)
        f-first     (future (in-trans "A" db first  entered-1 start-1 ended-1 commit-1 committed-1 ))
        f-second    (future (in-trans "B" db second entered-2 start-2 ended-2 commit-2 committed-2))]

    @entered-1
    @entered-2

    (deliver start-1 nil)
    @ended-1

    (deliver start-2 nil)
    (comment "Should be @ended-2"
             "but postgres does not support read uncommitted isolation"
             "level, which means the second transaction will block until"
             "the first is committed before continuing"
             "Well done Postgres, but annoying for our tests"
             )

    (deliver commit-1 nil)
    @committed-1

    (deliver commit-2 nil)
    @committed-2
    [@f-first @f-second]))

(fact "Two agents completing work at the same time still triggers the parent job"
  (let [parent-job-type    (uuid-str)
        child-job-type     (uuid-str)
        parent-job-id (uuid)]



    (let [[ first-commitment second-commitment]
          (sql/with-db-transaction
            [cnxn dev-db]
            (let [api ((sql-deps cnxn) :api)]
              (given-job-exists api parent-job-id parent-job-type
                                (job-req (uuid) {:child 1} child-job-type)
                                (job-req (uuid) {:child 2} child-job-type))
              [(request-work! api (uuid) {:job_type child-job-type} agent-details)
               (request-work! api (uuid) {:job_type child-job-type} agent-details)]))]

      (in-parallel-transactions
        dev-db
                            (fn [api]
                              (complete-work! api
                                              (first-commitment :commitment_id)
                                              (success [])))
                            (fn [api]
                              (complete-work! api
                                              (second-commitment :commitment_id)
                                              (success [])))))
    (sql/with-db-transaction
      [cnxn dev-db]
      (let [api ((sql-deps cnxn) :api)]
        (get-job api parent-job-id)
        => (contains {:dependencies_succeeded 2})

        (request-work! api (uuid) {:job_type parent-job-type} agent-details)
        => (contains {:job_id parent-job-id})))))


(future-fact "If there is no work to do, no work is handed out")
