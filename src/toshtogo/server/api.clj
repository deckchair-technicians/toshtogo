(ns toshtogo.server.api
  (:import (java.util UUID))
  (:require [clj-time.core :refer [now minus seconds]]
            [clojure.pprint :refer [pprint]]
            [toshtogo.util.core :refer [assoc-not-nil uuid ppstr debug]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.util.job-requests :refer [normalised-job-list]]))

(defn- recursively-add-dependencies
  "This is terribly inefficient"
  [persistence job]
  (when job
    (assoc job :dependencies (doall (map (partial recursively-add-dependencies persistence)
                                         (get-jobs persistence {:dependency_of_job_id (job :job_id)}))))))

(defn- merge-dependencies [contract persistence]
  (when contract
    (assoc contract :dependencies (get-jobs persistence {:dependency_of_job_id (contract :job_id)}))))

(defn- dependency-outcomes
  "This is incredibly inefficient"
  [persistence job-id]
  (assert (instance? UUID job-id) (str "job-id should be a UUID but was" (ppstr job-id)))
  (reduce (fn [outcomes dependency]
            (cons (dependency :outcome) outcomes))
          #{}
          (get-jobs persistence (dependencies-of job-id))))

(defn get-job [persistence job-id]
  (doall (recursively-add-dependencies persistence (first (get-jobs persistence {:job_id job-id})))))

(defn get-contract [persistence params]
  (cond-> (first (get-contracts persistence params))
          (params :with-dependencies) (merge-dependencies persistence)))

(defn new-contract! [persistence contract-req]
  (let [job-id                (contract-req :job_id)
        contract-due          (:contract_due contract-req (minus (now) (seconds 5)))
        last-contract         (get-contract persistence {:job_id job-id :latest_contract true})
        new-contract-ordinal   (if last-contract (inc (last-contract :contract_number)) 1)
        last-contract-outcome (:outcome last-contract)]

    (case last-contract-outcome
      :waiting
      (throw (IllegalStateException.
               (str "Job " job-id " has a waiting contract. Can't create a new one.")))

      :running
      (throw (IllegalStateException.
               (str "Job " job-id " has a running contract. Can't create a new one.")))

      :success
      (throw (IllegalStateException.
               (str "Job " job-id " has been completed. Can't create further contracts")))

      (insert-contract! persistence job-id new-contract-ordinal contract-due))))

(defn new-job! [persistence agent-details root-job]
  (let [root-and-dependencies (normalised-job-list root-job)]

    (insert-jobs! persistence root-and-dependencies agent-details)

    (doseq [job root-and-dependencies]
      (new-contract! persistence (contract-req (job :job_id))))

    (get-job persistence (root-job :job_id))))


(defn process-result! [persistence contract commitment-id result agent-details]
  (let [job-id (contract :job_id)]
    (case (:outcome result)
      :success
      nil

      :more-work
      (do
        ;Create new contract for parent job, which will be
        ;ready for work when dependencies complete
        (new-contract! persistence (contract-req job-id))
        (doseq [dependency (drop 1 (normalised-job-list (assoc contract :dependencies (result :dependencies))))]
          (if (:fungibility_group_id dependency)
            (if-let [existing-job (first (get-jobs persistence {:job_type             (:job_type dependency)
                                                                :request_body         (:request_body dependency)
                                                                :fungibility_group_id (:fungibility_group_id dependency)}))]
              (insert-dependency! persistence job-id (:job_id existing-job))
              (new-job! persistence agent-details dependency))

            (new-job! persistence agent-details dependency))))

      :try-later
      (new-contract! persistence (contract-req job-id (result :contract_due)))

      :error
      nil

      :cancelled
      nil)))

(defn complete-work!
  [persistence commitment-id result agent-details]
  (let [contract (get-contract persistence {:commitment_id commitment-id})
        job-id (:job_id contract)]

    (assert contract (str "Could not find commitment '" commitment-id "'"))

    (case (contract :outcome)
      :running
      (do
        (insert-result! persistence commitment-id result)
        (process-result! persistence contract commitment-id result agent-details))

      :cancelled
      nil

      (throw (IllegalStateException. (str "Contract in state '" (name (contract :outcome)) "' is not expecting a result. Contract\n" (ppstr contract) "\nResult:\n" (ppstr result)))))

    nil))

(defn request-work! [persistence commitment-id job-filter agent-details]
  (when-let [contract (get-contract
                        persistence
                        (assoc job-filter
                          :ready_for_work true
                          :order-by [:contract_created]))]
    (insert-commitment! persistence commitment-id (contract :contract_id) agent-details)

    (get-contract persistence {:commitment_id     commitment-id
                               :with-dependencies true})))

(defn pause-job! [persistence job-id agent-details]
  (let [job (get-job persistence job-id)]
    (assert job (str "no job " job-id))

    (when (= :waiting (:outcome job))
      (let [commitment-id (uuid)]
        (insert-commitment! persistence commitment-id (job :contract_id) agent-details)
        (complete-work! persistence commitment-id (cancelled) agent-details)))

    (when (= :running (:outcome job))
      (complete-work! persistence (:commitment_id job) (cancelled) agent-details)))

  (doseq [dependency  (get-jobs persistence {:dependency_of_job_id job-id})]
    (pause-job! persistence (dependency :job_id) agent-details)))