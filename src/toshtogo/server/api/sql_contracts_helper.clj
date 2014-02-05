(ns toshtogo.server.api.sql-contracts-helper
  (:require [flatland.useful.map :refer [map-vals-with-keys update]]
            [cheshire.core :as json]
            [clj-time.core :refer [now]]
            [clojure.string :as str]
            [toshtogo.server.api.protocol :refer :all]
            [toshtogo.server.agents.protocol :refer [agent!]]
            [toshtogo.util.sql :as tsql]
            [toshtogo.util.core :refer [uuid debug]]))

(defn contract-record [job-id contract-number contract-due]
  {:contract_id      (uuid)
   :job_id           job-id
   :contract_number  contract-number
   :contract_created (now)
   :contract_due     contract-due})

(defn outcome-record [contract result]
  {:job_id      (contract :job_id)
   :result_body (json/generate-string (result :result))})

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

(def latest-contract-sql
  "contract_number = (
     select max(contract_number)
     from contracts c
     where c.job_id = contracts.job_id)")

(def tag-sql
  "     contracts.job_id in (
          select
            distinct (jobs.job_id)
          from
            jobs
          join job_tags
            on jobs.job_id = job_tags.job_id
          where tag in (:tags))")

(defn expand-shortcut-params [params]
  (cond-> params
          (params :ready_for_work) (assoc :outcome :waiting)
          (params :ready_for_work) (assoc :min_due_time (now))))

(defn contracts-where-fn [params]
  (reduce
   (fn [[out-params clauses] [k v]]
     (case k
       :outcome
       (if (= :waiting v)
         [out-params
          (cons "outcome is null and commitment_id is null" clauses)]
         [(assoc out-params k v)
          (cons "outcome = :outcome" clauses)])

       :tags
       [(assoc out-params k (map name v))
        (cons tag-sql clauses)]

       :commitment_id
       [(assoc out-params k v)
        (cons "commitment_id = :commitment_id" clauses)]

       :job_id
       [(assoc out-params k v)
        (cons "contracts.job_id = :job_id" clauses)]

       :min_due_time
       [(assoc out-params k v)
        (cons "contracts.contract_due <= :min_due_time" clauses)]

       :latest_contract
       (if v
         [out-params
          (cons latest-contract-sql clauses)]
         [out-params clauses])

       [(assoc out-params k v) clauses]))
   [{} []]
   (expand-shortcut-params params)))


(defn normalise-record [contract]
  (-> contract
      (update :outcome #(or (keyword %) :waiting))
      (update :request_body #(json/parse-string % keyword))))

(defn commitment-record [commitment-id contract agent]
  {:commitment_id       commitment-id
   :commitment_contract (contract :contract_id)
   :commitment_agent    (agent :agent_id)
   :contract_claimed    (now)})

(defn merge-dependencies [contract api]
  (when contract
    (assoc contract :dependencies (get-jobs api {:dependency_of_job_id (contract :job_id)}))))

(defn insert-commitment!
  [cnxn agents commitment-id contract agent-details]
  (tsql/insert!
   cnxn
   :agent_commitments
   (commitment-record
    commitment-id
    contract
    (agent! agents agent-details))))

(defn ensure-commitment-id!
  [cnxn agents contract agent-details]
  (if-let [commitment-id (contract :commitment_id)]
    commitment-id
    (let [commitment-id (uuid)]
      (insert-commitment! cnxn agents commitment-id contract agent-details)
      commitment-id)))
