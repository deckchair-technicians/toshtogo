(ns toshtogo.jobs)

(defn job-map [id agent-details body tags]
  {:job_id id
   :agent agent-details
   :tags tags
   :request_body body})

(defprotocol Jobs
  "Idempotently create a new job."
  (put-job! [this job])
  (get-job [this job-id]))
