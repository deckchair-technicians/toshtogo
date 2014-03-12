(ns toshtogo.server.persistence.protocol
  (:import (java.util UUID))
  (:require [clojure.pprint :refer [pprint]]))

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

(defn dependencies-of [job-id]
  {:dependency_of_job_id job-id :order-by :job_created})

(defprotocol Persistence
  (insert-jobs! [this jobs agent-details])

  (insert-contract!   [this job-id contract-number contract-due])
  (insert-commitment! [this commitment-id contract-id agent-details])
  (upsert-heartbeat!  [this commitment-id])
  (insert-result!     [this commitment-id result])

  (get-jobs      [this params])
  (get-contracts [this params]))
