(ns toshtogo.server.api
  (:require [clj-time.core :refer [now minus seconds]]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :refer [postwalk]]
            [swiss.arrows :refer :all]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.json :as json]
            [toshtogo.util.core :refer [assoc-not-nil uuid ppstr debug]]
            [toshtogo.client.protocol :refer [cancelled]]
            [toshtogo.server.logging :refer :all]
            [toshtogo.server.persistence.protocol :as pers]
            [toshtogo.server.preprocessing :refer [normalise-job-tree replace-fungible-jobs-with-existing-job-ids collect-dependencies collect-new-jobs]])
  (:import [java.util UUID]
           [toshtogo.server.util UniqueConstraintException]))

(defprotocol Api
  (new-contract! [this contract-req])
  (new-jobs! [this jobs])
  (new-dependencies! [this parent-job-or-contract]
                     "Assumes job-or-contract already exists. Navigates the dependency tree,
                     creating new jobs and dependencies as required.")
  (new-root-job! [this job])
  (process-result! [this contract result])
  (request-work! [this commitment-id job-query])
  (complete-work! [this commitment-id result])
  (pause-job! [this job-id]))


(defn- dependency-outcomes
  "This is incredibly inefficient"
  [persistence job-id]
  (assert (instance? UUID job-id) (str "job-id should be a UUID but was" (ppstr job-id)))
  (reduce (fn [outcomes dependency]
            (cons (dependency :outcome) outcomes))
          #{}
          (pers/get-jobs persistence (pers/dependencies-of job-id))))

(defn to-job-record [job-request]
  (-> job-request
      (dissoc :dependencies
              :existing_job_dependencies
              :parent_job_id
              :fungible_under_parent
              :should-funge
              :contract_due)
      (update :request_body json/encode)))

(defn api [persistence logger agent-details]
  (reify Api
    (new-contract! [_ contract-req]
      (let [job-id (contract-req :job_id)
            contract-due (or (:contract_due contract-req) (minus (now) (seconds 5)))
            last-contract (pers/get-contract persistence {:job_id job-id :latest_contract true})
            new-contract-ordinal (if last-contract (inc (last-contract :contract_number)) 1)
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

          (pers/insert-contract! persistence job-id new-contract-ordinal contract-due))))

    (new-jobs! [this jobs]
      (let [job-records (map to-job-record jobs)]

        (doseq [ev (map new-job-event job-records)]
          (log logger ev))

        (pers/insert-jobs! persistence job-records)
        (doseq [job jobs]
          (new-contract! this (pers/contract-req (job :job_id) (job :contract_due))))))

    (new-dependencies!
      [this parent-job-or-contract]
      (let [agent-id (:agent_id (pers/agent! persistence agent-details))
            parent-job-or-contract (-> parent-job-or-contract
                                       (normalise-job-tree agent-id)
                                       (replace-fungible-jobs-with-existing-job-ids persistence))

            dependency-records (collect-dependencies parent-job-or-contract)
            new-jobs (collect-new-jobs parent-job-or-contract)]

        (new-jobs! this new-jobs)

        (doseq [dependency-record dependency-records]
          (pers/insert-dependency! persistence
                              dependency-record))))

    (new-root-job! [this job]
      (let [job (-> job
                    (assoc :home_tree_id (uuid))
                    (normalise-job-tree (:agent_id (pers/agent! persistence agent-details))))]

        (pers/insert-tree! persistence (:home_tree_id job) (:job_id job))
        (new-jobs! this [job])

        (new-dependencies! this job)))

    (process-result! [this contract result]
      (let [job-id (contract :job_id)]
        (case (:outcome result)
          :success
          nil

          :more-work
          (do
            ;Create new contract for parent job, which will be
            ;ready for work when dependencies complete
            (new-contract! this (pers/contract-req job-id))

            (new-dependencies!
              this
              (-> contract
                  (assoc :dependencies (result :dependencies))
                  (assoc :existing_job_dependencies (result :existing_job_dependencies)))))

          :try-later
          (new-contract! this (pers/contract-req job-id (result :contract_due)))

          :error
          nil

          :cancelled
          nil)))

    (request-work! [this commitment-id job-query]
      (let [insert-and-get (fn [contract]
                             (when (pers/insert-commitment! persistence commitment-id (:contract_id contract) agent-details)
                               (log logger (commitment-started-event commitment-id contract agent-details))

                               (pers/get-contract persistence {:commitment_id     commitment-id
                                                               :with-dependencies true})))

            modified-query (-> job-query
                               (assoc :ready_for_work true)
                               (update :order-by #(or % [:job_created])))]

        (->> (pers/get-contracts persistence modified-query)
             (map insert-and-get)
             (filter (comp not nil?))
             first)))

    (complete-work! [this commitment-id result]
      (let [contract (pers/get-contract persistence {:commitment_id commitment-id})]

        (assert contract (str "Could not find commitment '" commitment-id "'"))

        (case (contract :outcome)
          :running
          (do
            (log logger (commitment-result-event contract agent-details result))
            (pers/insert-result! persistence commitment-id result)
            (process-result! this contract result))

          :cancelled
          nil

          (throw (IllegalStateException. (str "Contract in state '" (name (contract :outcome)) "' is not expecting a result. Contract\n" (ppstr contract) "\nResult:\n" (ppstr result)))))

        nil))

    (pause-job! [this job-id]
      (let [job (pers/get-job persistence job-id)]
        (assert job (str "no job " job-id))

        (when (= :waiting (:outcome job))
          (let [commitment-id (uuid)]
            (pers/insert-commitment! persistence commitment-id (job :contract_id) agent-details)
            (complete-work! this commitment-id (cancelled))))

        (when (= :running (:outcome job))
          (complete-work! this (:commitment_id job) (cancelled))))

      (doseq [dependency (pers/get-jobs persistence {:dependency_of_job_id job-id})]
        (pause-job! this (dependency :job_id))))))
