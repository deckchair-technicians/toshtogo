(ns toshtogo.api)

(defn job-map
  [id agent-details body tags]
  {:job_id id
   :agent agent-details
   :tags tags
   :request_body body})

(defn success [response-body]
  {:outcome :success
   :result  response-body})

(defn error [error-text]
  {:outcome :error
   :error   error-text})

(defn add-dependencies [dependency & dependencies]
  {:outcome      :more-work
   :dependencies (concat [dependency] dependencies)})

(defn depends-on [contract]
  {:depends_on_job_id (contract :job_id) :order-by :job_created})

(defn dependencies-of [job]
  {:dependency_of_job_id (job :job_id) :order-by :job_created})

(defprotocol Toshtogo
  (put-job! [this job])
  (get-job [this job-id])
  (get-jobs [this params])

  (get-contracts [this params])
  (get-contract  [this params])
  (new-contract! [this job-id])

  (request-work! [this commitment-id tags agent])
  (complete-work! [this commitment-id result]))
