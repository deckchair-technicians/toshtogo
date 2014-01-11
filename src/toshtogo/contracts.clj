(ns toshtogo.contracts
  (:require [flatland.useful.map :refer [map-vals-with-keys update]]
            [clj-time.core :refer [now]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [toshtogo.agents :refer [agent!]]
            [toshtogo.sql :as tsql]
            [toshtogo.util :refer [uuid debug]]))

(defprotocol Contracts
  (new-contract! [this job-id])
  (get-contracts [this params])
  (request-work! [this commitment-id tags agent])
  (complete-work! [this commitment-id result]))

(defn contract-record [job-id]
  {:contract_id (uuid) :job_id job-id :contract_created (now)})

(defn contracts-sql [params]
  (cond->
   "select
     *

   from
     contracts

   left join
     agent_commitments commitments
     on contracts.contract_id = commitments.commitment_contract

   left join
     commitment_outcomes
     on commitments.commitment_id = commitment_outcomes.outcome_id "
   (params :return-jobs) (str "left join jobs on contracts.job_id = jobs.job_id")))

(def tag-sql
  "     contracts.job_id in (
          select
            distinct (jobs.job_id)
          from
            jobs
          join job_tags
            on jobs.job_id = job_tags.job_id
          where tag in (:tags))")

(defn where-clauses [params]
  (reduce
   (fn [[out-params clauses] [k v]]
     (case k
       :state
       (if (= :waiting v)
         [out-params
          (cons "contract_state is null and commitment_id is null" clauses)]
         [(assoc out-params :contract_state v)
          (cons "contract_state = :contract_state" clauses)])
       :tags
       [(assoc out-params :tags (map name v))
        (cons tag-sql clauses)]
       :commitment_id
       [(assoc out-params :commitment_id v)
        (cons "commitment_id = :commitment_id" clauses)]
       [(assoc out-params k v) clauses]))
   [{} []]
   params))


(defn qualify [sql params]
  (let [[out-params where-clauses] (where-clauses params)]
    [(cond-> sql
             (not-empty where-clauses)
             (str "\n    where\n      "    (str/join "\n      and " where-clauses))

             (:order-by-desc params)
             (str "\n    order by " (name (:order-by-desc params)) " desc"))
     out-params]))

(defn normalise-record [contract]
  (-> contract
      (update :contract_state #(or (keyword %) :waiting))
      (update :request_body #(json/parse-string % keyword))))

(defn commitment-record [commitment-id contract agent]
  {:commitment_id       commitment-id
   :commitment_contract (contract :contract_id)
   :commitment_agent    (agent :agent_id)
   :contract_claimed    (now)})

(defn SqlContracts [cnxn agents]
  (reify
    Contracts
    (new-contract! [this job-id]
      (tsql/insert! cnxn :contracts (contract-record job-id)))

    (get-contracts [this params]
      (map
       normalise-record
       (apply tsql/query
              cnxn
              (qualify  (contracts-sql params) params))))

    (request-work! [this commitment-id tags agent-details]
      (if-let [contract (first (get-contracts
                                this
                                {:state :waiting
                                 :tags tags
                                 :order-by-desc :contract_created}))]
        (tsql/insert!
         cnxn
         :agent_commitments
         (commitment-record commitment-id
                            contract
                            (agent! agents agent-details)))

        (first (get-contracts this {:commitment_id commitment-id}))))

    (complete-work! [this commitment-id result]
      (if-let [contract (first (get-contracts this {:commitment_id commitment-id}))]
        (let [outcome (result :outcome)
              job-id  (contract :job_id)]

          (tsql/insert! cnxn
                        :commitment_outcomes
                        {:outcome_id commitment-id
                         :error_details (result :error)
                         :contract_finished (now)
                         :contract_state outcome})

          (when (= :success outcome)
            (tsql/insert! cnxn
                          :job_results
                          {:job_id (contract :job_id)
                           :result_body (json/generate-string (result :result))}))

          nil)

        (throw (NullPointerException. (str "Could not find commitment '" commitment-id "'")))))))

(defn sql-contracts [cnxn agents]
  (SqlContracts cnxn agents))
