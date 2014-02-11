(ns toshtogo.server.api.sql-jobs-helper
  (:require [flatland.useful.map :refer [update update-each]]
            [pallet.map-merge :refer [merge-keys]]
            [clj-time.core :refer [now]]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]
            [toshtogo.server.api.protocol :refer :all]
            [toshtogo.util.core :refer [uuid debug]]
            [toshtogo.util.sql :as tsql])
  (:import [toshtogo.util OptimisticLockingException]))

(defn dependency-record [parent-job-id child-job]
  {:dependency_id (uuid)
   :parent_job_id parent-job-id
   :child_job_id (child-job :job_id)})

(defn job-record [id job-type agent-id body notes]
  {:job_id            id
   :job_type          job-type
   :requesting_agent  agent-id
   :job_created       (now)
   :request_body      (json/generate-string body)
   :notes             notes})

(def job-sql
  "select
     *

   from
     jobs

   left join
     job_results
     on jobs.job_id = job_results.job_id

   left join
     job_tags
     on jobs.job_id = job_tags.job_id

   left join
     contracts
     on jobs.job_id = contracts.job_id
     and contracts.contract_number = (
       select max(contract_number)
       from contracts c
       where jobs.job_id = c.job_id)

   left join
     agent_commitments commitments
     on contracts.contract_id = commitments.commitment_contract

   left join
     commitment_outcomes
     on commitments.commitment_id = commitment_outcomes.outcome_id")

(def depends-on-sql
  "jobs.job_id in (
     select parent_job_id
     from job_dependencies
     where child_job_id = :depends_on_job_id)")

(def dependency-of-sql
  "jobs.job_id in (
     select child_job_id
     from job_dependencies
     where parent_job_id = :dependency_of_job_id)")

(defn dependency-outcomes
  "This is incredibly inefficient"
  [api job]
  (reduce (fn [outcomes dependency]
            (cons (dependency :outcome) outcomes))
          #{}
          (get-jobs api (dependencies-of job))))


(defn collect-tags [job row]
  (if job
    (-> job
        (update :tags #(conj % (row :tag) )))
    (assoc row :tags #{(row :tag)})))

(defn expand-dependency [job-agent job]
  (-> job
      (assoc :job_id (:job_id job (uuid)))
      (assoc :agent job-agent)))

(defn job-outcome [job]
  (if (:outcome job)
    (:outcome job)
    (if (:contract_claimed job)
      :running
      :waiting)))

(defn fix-job-outcome [job]
  (assoc job :outcome (job-outcome job)))

(defn normalise-job [job]
  (-> job
      (dissoc :tag)
      (dissoc :job_id_2 :job_id_3 :job_id_4 :commitment_contract :outcome_id)
      (update :outcome keyword)
      (fix-job-outcome)
      (update :last_heartbeat #(when % (tc/from-sql-time %)))
      (update-each [:request_body :result_body] #(json/parse-string % keyword))))

(defn from-sql [job-with-tags]
  (normalise-job
   (reduce collect-tags
           nil
           job-with-tags)))

(defn jobs-where-fn [params]
  (reduce
   (fn [[out-params clauses] [k v]]
     (case k
       :job_id
       [(assoc out-params :job_id v)
        (cons "jobs.job_id = :job_id" clauses)]

       :depends_on_job_id
       [(assoc out-params :depends_on_job_id v)
        (cons depends-on-sql clauses)]

       :dependency_of_job_id
       [(assoc out-params :dependency_of_job_id v)
        (cons dependency-of-sql clauses)]

       [(assoc out-params k v) clauses]))
   [{} []]
   params))

(defn get-succeeded-dependency-count [cnxn job-id]
  (:dependencies_succeeded (tsql/query-single
                                          cnxn
                                          "select dependencies_succeeded from jobs where job_id = :job_id"
                                          {:job_id job-id})))

(defn incremement-succeeded-dependency-count! [cnxn job-id]
  (let [prev-count  (get-succeeded-dependency-count cnxn job-id)]
    (when-not (= 1 (first (tsql/update! cnxn :jobs
                                        {:dependencies_succeeded (inc prev-count)}
                                                 ["dependencies_succeeded = ? and job_id = ?"
                                                  prev-count  job-id])))
      (throw (OptimisticLockingException.
              (str "dependencies_succeeded updated in another transaction for job "
                   job-id))))))

(defn handle-contract-completion! [cnxn api contract]
  (when (= :success (contract :outcome))
    (doseq [parent-job (get-jobs api (depends-on contract))]
      (incremement-succeeded-dependency-count! cnxn  (parent-job :job_id))
      (let [dependency-outcomes (dependency-outcomes api parent-job)]
        (when (every? #(= :success %)  dependency-outcomes)
          (new-contract! api (contract-req (parent-job :job_id))))))))

(defn handle-new-job! [api job]
  (when-not (job :dependencies)
    (new-contract! api (contract-req (job :job_id)))))
