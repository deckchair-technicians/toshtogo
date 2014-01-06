(ns toshtogo.jobs
  (:require [clojure.java.jdbc :as sql]
             [clj-time.core :refer [now]]
             [toshtogo.util :refer [uuid]]
             [toshtogo.agents :refer [agent!]]
             [toshtogo.contracts :refer [new-contract!]]
             [toshtogo.sql :as tsql]
             [cheshire.core :as json]))

(defprotocol Jobs
  "Idempotently create a new job."
  (put-job! [this job])
  (get-job [this job-id]))

(defn job-record [id agent-id body]
  {:job_id id :requesting_agent agent-id :created (now) :body (json/generate-string body)})

(def get-job-sql "select * from jobs where job_id = :job-id")

(deftype SqlJobs [cnxn agents contracts]
  Jobs
  (put-job! [this job]
    (let [job-id          (job :id)
          job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
          job-agent       (agent! agents (job :agent))
          job-row         (job-record job-id (job-agent :agent_id) (job :body))]

      (tsql/insert! cnxn :jobs job-row)
      (apply tsql/insert! cnxn :job_tags job-tag-records)

      (new-contract! contracts job-id)

      job))

  (get-job [this job-id]
    (tsql/query cnxn get-job-sql {:job-id job-id})))

(defn sql-jobs [cnxn agents contracts]
  (SqlJobs. cnxn agents contracts))
