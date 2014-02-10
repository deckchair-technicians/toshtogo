(ns toshtogo.server.api.protocol
  (:require [toshtogo.util.core :refer [assoc-not-nil]]))

(defn job-req
  [id agent-details body job-type & {:keys [dependencies notes tags]}]
  (-> {:job_id       id
           :agent        agent-details
           :job_type     job-type
           :request_body body}
      (assoc-not-nil :dependencies dependencies
                     :notes notes
                     :tags tags)))

(defn contract-req
  ([job-id]
     {:job_id job-id})
  ([job-id contract-due]
     (assoc (contract-req job-id) :contract_due contract-due)))

(defn success [response-body]
  {:outcome :success
   :result  response-body})

(defn error [error-text]
  {:outcome :error
   :error   error-text})

(defn cancelled []
  {:outcome :cancelled})

(defn add-dependencies [dependency & dependencies]
  {:outcome      :more-work
   :dependencies (concat [dependency] dependencies)})

(defn try-later
  ([contract-due]
     {:outcome       :try-later
      :contract_due contract-due})
  ([contract-due error-text]
     (assoc (try-later contract-due)
       :error error-text)))

(defn depends-on [contract]
  {:depends_on_job_id (contract :job_id) :order-by :job_created})

(defn dependencies-of [job]
  {:dependency_of_job_id (job :job_id) :order-by :job_created})

(defprotocol Toshtogo
  (put-job!   [this job])
  (get-job    [this job-id])
  (get-jobs   [this params])
  (pause-job! [this job-id agent-details])

  (get-contracts [this params])
  (get-contract  [this params])
  (new-contract! [this contract])

  (request-work!  [this commitment-id job-type agent])
  (heartbeat!     [this commitment-id])
  (complete-work! [this commitment-id result]))
