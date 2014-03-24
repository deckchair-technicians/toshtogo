(ns toshtogo.server.api
  (:import (java.util UUID))
  (:require [clj-time.core :refer [now minus seconds]]
            [toshtogo.util.core :refer [assoc-not-nil uuid ppstr debug]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.util.job-requests :refer [flattened-dependencies]]))

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
               (str "Job " job-id " has an unfinished contract. Can't create a new one.")))
      :success
      (throw (IllegalStateException.
               (str "Job " job-id " has been completed. Can't create further contracts")))

      (insert-contract! persistence job-id new-contract-ordinal contract-due))))

(defn new-job! [persistence agent-details job]
  (let [jobs           (concat [(dissoc job :dependencies)] (flattened-dependencies job))
        parent-job-ids (set (filter (comp not nil?) (map :parent_job_id jobs)))
        leaf-jobs (filter #(not (parent-job-ids (:job_id %))) jobs)]

    (insert-jobs! persistence jobs agent-details)

    (doseq [leaf-job leaf-jobs]
      (new-contract! persistence (contract-req (leaf-job :job_id))))

    (get-job persistence (job :job_id))))


(defn complete-work!
  [persistence commitment-id result]
  (let [contract (get-contract persistence {:commitment_id commitment-id})
        job-id (:job_id contract)
        agent-details (:agent result)]

    (assert contract (str "Could not find commitment '" commitment-id "'"))

    (case (contract :outcome)
      :running
      (do
        (insert-result! persistence commitment-id result)

        (case (:outcome result)
          :success
          (doseq [parent-job (get-jobs persistence (depends-on contract))]
            (let [dependency-outcomes (dependency-outcomes persistence (parent-job :job_id))]
              (when (every? #(= :success %)  dependency-outcomes)
                (new-contract! persistence (contract-req (parent-job :job_id))))))

          :more-work
          (doseq [job (flattened-dependencies {:job_id       job-id
                                               :dependencies (result :dependencies)})]
            (new-job! persistence agent-details job))

          :try-later
          (new-contract! persistence (contract-req job-id (result :contract_due)))

          :error
          nil

          :cancelled
          nil))

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
      (let [commitment (request-work! persistence (uuid) {:job_id job-id} agent-details)]
        (complete-work! persistence (:commitment_id commitment) (cancelled))))

    (when (= :running (:outcome job))
      (complete-work! persistence (:commitment_id job) (cancelled))))

  (doseq [dependency  (get-jobs persistence {:dependency_of_job_id job-id})]
    (pause-job! persistence (dependency :job_id) agent-details)))