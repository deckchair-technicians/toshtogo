(ns toshtogo.jobs
  (:require [clojure.java.jdbc :as sql]
             [clj-time.core :refer [now]]
             [toshtogo.util :refer [uuid]]
             [toshtogo.agents :refer [agent!]]
             [toshtogo.contracts :refer [new-contract!]]
             [toshtogo.sql :as tsql]))

(defprotocol Jobs
  (new-job! [this job])
  (get-job [this job-id]))

(defn job-record [id agent-id]
  {:job_id id :requesting_agent agent-id :created (now)})

(def get-job-sql "select * from jobs where job_id = :job-id")

(deftype SqlJobs [cnxn agents contracts]
  Jobs
  (new-job! [this job]

    (let [job-id          (uuid)
          job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
          job-agent       (agent! agents (job :agent))
          job             (job-record job-id (job-agent :agent_id))]

      (tsql/insert! cnxn :jobs job)
      (apply tsql/insert! cnxn :job_tags job-tag-records)

      (new-contract! contracts job-id)

      job))

  (get-job [this job-id]
    (let [job (tsql/query cnxn get-job-sql {:job-id job-id})]
      (println "JOB" job)
      job)))

(defn sql-jobs [cnxn agents contracts]
  (SqlJobs. cnxn agents contracts))
