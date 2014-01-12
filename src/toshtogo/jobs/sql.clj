(ns toshtogo.jobs.sql
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
            [cheshire.core :as json]
            [flatland.useful.map :refer [update update-each]]
            [toshtogo.util :refer [uuid debug]]
            [toshtogo.jobs :refer :all]
            [toshtogo.agents :refer [agent!]]
            [toshtogo.contracts :refer [new-contract!]]
            [toshtogo.sql :as tsql]))

(defn job-record [id agent-id body]
  {:job_id id
   :requesting_agent agent-id
   :job_created (now)
   :request_body (json/generate-string body)})


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
     on commitments.commitment_id = commitment_outcomes.outcome_id

   where
     jobs.job_id = :job_id")

(defn SqlJobs [cnxn on-new-job! agents]
  (reify Jobs
    (put-job! [this job]
      (let [job-id          (job :job_id)
            job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
            job-agent       (agent! agents (job :agent))
            job-row         (job-record job-id (job-agent :agent_id) (job :request_body))]

        (tsql/insert! cnxn :jobs job-row)
        (apply tsql/insert! cnxn :job_tags job-tag-records)

        (on-new-job! job)

        job))

    (get-job [this job-id]
      (let [job-with-tags (doall (tsql/query cnxn job-sql {:job_id job-id}))
            job           (first job-with-tags)
            tags          (map :tag job-with-tags)]
        (-> job
            (dissoc :tag)
            (assoc :tags (set tags))
            (dissoc :job_id_2 :job_id_3 :commitment_contract :outcome_id)
            (update :outcome keyword)
            (update-each [:request_body :result_body] #(json/parse-string  % keyword)))))))

(defn sql-jobs [cnxn agents contracts]
  (SqlJobs cnxn #(new-contract! contracts (% :job_id))  agents))
