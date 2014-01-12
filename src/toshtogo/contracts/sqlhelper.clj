(ns toshtogo.contracts.sqlhelper
  (:require [flatland.useful.map :refer [map-vals-with-keys update]]
            [cheshire.core :as json]
            [clj-time.core :refer [now]]
            [clojure.string :as str]
            [toshtogo.util :refer [uuid debug]]))

(defn contract-record [job-id contract-number]
  {:contract_id      (uuid)
   :job_id           job-id
   :contract_number  contract-number
   :contract_created (now)})

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

(defn where-clauses [params]
  (reduce
   (fn [[out-params clauses] [k v]]
     (case k

       :outcome
       (if (= :waiting v)
         [out-params
          (cons "outcome is null and commitment_id is null" clauses)]
         [(assoc out-params :outcome v)
          (cons "outcome = :outcome" clauses)])

       :tags
       [(assoc out-params :tags (map name v))
        (cons tag-sql clauses)]

       :commitment_id
       [(assoc out-params :commitment_id v)
        (cons "commitment_id = :commitment_id" clauses)]

       :job_id
       [(assoc out-params :job_id v)
        (cons "contracts.job_id = :job_id" clauses)]

       :latest_contract
       (if v
         [out-params
          (cons latest-contract-sql clauses)]
         [out-params clauses])

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
      (update :outcome #(or (keyword %) :waiting))
      (update :request_body #(json/parse-string % keyword))))

(defn commitment-record [commitment-id contract agent]
  {:commitment_id       commitment-id
   :commitment_contract (contract :contract_id)
   :commitment_agent    (agent :agent_id)
   :contract_claimed    (now)})
