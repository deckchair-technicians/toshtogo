(ns toshtogo.jobs
  (:require [clojure.java.jdbc :as sql]
             [clj-time.core :refer [now]]
             [toshtogo.util :refer [uuid debug]]
             [toshtogo.agents :refer [agent!]]
             [toshtogo.contracts :refer [new-contract!]]
             [toshtogo.sql :as tsql]
             [cheshire.core :as json]
             [flatland.useful.map :refer [update update-each]]))

(defn job [id agent-details body tags]
  {:job_id id
   :agent agent-details
   :tags tags
   :request_body body})

(defprotocol Jobs
  "Idempotently create a new job."
  (put-job! [this job])
  (get-job [this job-id]))

(defn job-record [id agent-id body]
  {:job_id id
   :requesting_agent agent-id
   :job_created (now)
   :request_body (json/generate-string body)})

(def get-job-sql
  "select
     *
   from
     jobs
   left join
     job_results
     on jobs.job_id = job_results.job_id
   where
     jobs.job_id = :job_id")

(defn SqlJobs [cnxn agents contracts]
  (reify Jobs
    (put-job! [this job]
      (let [job-id          (job :job_id)
            job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
            job-agent       (agent! agents (job :agent))
            job-row         (job-record job-id (job-agent :agent_id) (job :request_body))]

        (tsql/insert! cnxn :jobs job-row)
        (apply tsql/insert! cnxn :job_tags job-tag-records)

        (new-contract! contracts job-id)

        job))

    (get-job [this job-id]
      (-> (tsql/query-single cnxn get-job-sql {:job_id job-id})
          (update-each [:request_body :result_body] #(json/parse-string  % keyword))))))

(defn sql-jobs [cnxn agents contracts]
  (SqlJobs cnxn agents contracts))
