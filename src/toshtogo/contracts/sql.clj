(ns toshtogo.contracts.sql
  (:require [clj-time.core :refer [now]]
            [toshtogo.contracts :refer :all]
            [toshtogo.contracts.sqlhelper :refer :all]
            [toshtogo.agents :refer :all]
            [toshtogo.sql :as tsql]))

(defn unfinished-contract [job-id]
  (IllegalStateException.
   (str "Job " job-id " has an unfinished contract. Can't create a new one.")))

(defn job-finished [job-id]
 (IllegalStateException.
                  (str "Job " job-id " has been completed. Can't create further contracts")) )

(defn SqlContracts [cnxn agents]
  (reify
    Contracts
    (get-contracts [this params]
      (map
       normalise-record
       (apply tsql/query
              cnxn
              (qualify  (contracts-sql params) params))))

    (get-contract [this params]
      (first (get-contracts this params)))

    (new-contract! [this job-id]
      (let [last-contract       (get-contract this {:job_id job-id :latest_contract true})
            new-contract-number (if last-contract (inc (last-contract :contract_number)) 1)
            last-contract-outcome (:outcome last-contract)]
        (case last-contract-outcome
          :waiting
          (throw (unfinished-contract job-id))
          :success
          (throw (job-finished job-id))
          (tsql/insert! cnxn :contracts (contract-record job-id new-contract-number)))))



    (request-work! [this commitment-id tags agent-details]
      (when-let [contract  (get-contract
                            this
                            {:outcome         :waiting
                             :tags          tags
                             :order-by-desc :contract_created})]
        (tsql/insert!
         cnxn
         :agent_commitments
         (commitment-record commitment-id
                            contract
                            (agent! agents agent-details))))

      (get-contract this {:commitment_id commitment-id
                          :return-jobs   true}))

    (complete-work! [this commitment-id result]
      (if-let [contract (get-contract this {:commitment_id commitment-id})]
        (let [outcome (result :outcome)
              job-id  (contract :job_id)]

          (tsql/insert! cnxn
                        :commitment_outcomes
                        {:outcome_id        commitment-id
                         :error             (result :error)
                         :contract_finished (now)
                         :outcome           outcome})

          (when (= :success outcome)
            (tsql/insert! cnxn
                          :job_results
                          (outcome-record contract result)))

          nil)

        (throw (NullPointerException. (str "Could not find commitment '" commitment-id "'")))))))

(defn sql-contracts [cnxn agents]
  (SqlContracts cnxn agents))
