(ns toshtogo.server.api
  (:import (java.util UUID))
  (:require [clj-time.core :refer [now minus seconds]]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [postwalk]]
            [swiss.arrows :refer :all]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.json :refer [encode]]
            [toshtogo.util.core :refer [assoc-not-nil uuid ppstr debug]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.preprocessing :refer [normalise-job-tree replace-fungible-jobs-with-existing-job-ids collect-dependencies collect-new-jobs]]))

(defn- assoc-dependencies
  [persistence job]
  (when job
    (assoc job :dependencies (get-jobs persistence {:dependency_of_job_id (job :job_id)}))))

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
  (assoc-dependencies persistence (first (get-jobs persistence {:job_id job-id}))))

(defn get-contract [persistence params]
  (cond-> (first (get-contracts persistence params))
          (params :with-dependencies) (merge-dependencies persistence)))

(defn new-contract! [persistence contract-req]
  (let [job-id                (contract-req :job_id)
        contract-due          (or (:contract_due contract-req) (minus (now) (seconds 5)))
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

(defn new-jobs! [persistence jobs]
  (insert-jobs! persistence jobs)
  (doseq [job jobs]
    (new-contract! persistence (contract-req (job :job_id) (job :contract_due)))))

(defn new-dependencies!
      "Assumes job-or-contract already exists. Navigates the dependency tree,
      creating new jobs and dependencies as required."
  [persistence agent-details parent-job-or-contract]
  (let [agent-id (:agent_id (agent! persistence agent-details))
        parent-job-or-contract (-> parent-job-or-contract
                                  (normalise-job-tree agent-id)
                                 (replace-fungible-jobs-with-existing-job-ids persistence))

        dependency-records (collect-dependencies parent-job-or-contract)
        new-jobs (collect-new-jobs parent-job-or-contract)]

    (new-jobs! persistence new-jobs)

    (doseq [dependency-record dependency-records]
      (insert-dependency! persistence
                          dependency-record))))

(defn new-root-job! [persistence agent-details job]
  (let [job (-> job
              (assoc :home_tree_id (uuid))
              (normalise-job-tree (:agent_id (agent! persistence agent-details))))]

    (insert-tree! persistence (:home_tree_id job) (:job_id job))
    (new-jobs! persistence [job])

    (new-dependencies! persistence agent-details job)))

(defn process-result! [persistence contract result agent-details]
  (let [job-id (contract :job_id)]
    (case (:outcome result)
      :success
      nil

      :more-work
      (do
        ;Create new contract for parent job, which will be
        ;ready for work when dependencies complete
        (new-contract! persistence (contract-req job-id))

        (new-dependencies!
          persistence
          agent-details
          (-> contract
               (assoc :dependencies (result :dependencies))
               (assoc :existing_job_dependencies (result :existing_job_dependencies)))))

      :try-later
      (new-contract! persistence (contract-req job-id (result :contract_due)))

      :error
      nil

      :cancelled
      nil)))

(defn get-tree [persistence tree-id]
  (let [params {:tree_id tree-id
                :fields  [:jobs.job_id :jobs.job_name :jobs.job_type :outcome]}]
    {:root_job (first (get-jobs persistence {:root_of_tree_id tree-id
                                             :fields          [:jobs.job_id]}))
     :jobs     (get-jobs persistence params)
     :links    (get-dependency-links persistence params)}))

(defn complete-work!
  [persistence commitment-id result agent-details]
  (let [contract (get-contract persistence {:commitment_id commitment-id})]

    (assert contract (str "Could not find commitment '" commitment-id "'"))

    (case (contract :outcome)
      :running
      (do
        (insert-result! persistence commitment-id result)
        (process-result! persistence contract result agent-details))

      :cancelled
      nil

      (throw (IllegalStateException. (str "Contract in state '" (name (contract :outcome)) "' is not expecting a result. Contract\n" (ppstr contract) "\nResult:\n" (ppstr result)))))

    nil))

(defn request-work! [persistence commitment-id job-query agent-details]
  (when-let [contract (get-contract
                       persistence
                       (merge
                        {:ready_for_work true
                         :order-by [:job_created]}
                        job-query))]
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
