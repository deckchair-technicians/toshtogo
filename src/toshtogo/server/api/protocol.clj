(ns toshtogo.server.api.protocol
  (:import (java.util UUID))
  (:require [clojure.pprint :refer [pprint]]
            [toshtogo.util.core :refer [assoc-not-nil uuid ppstr debug]]
            [toshtogo.server.util.job-requests :refer [flattened-dependencies]]))

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

(defprotocol Toshtogo
  (insert-jobs!   [this jobs agent-details])
  (get-jobs   [this params])

  (get-contracts [this params])
  (new-contract! [this contract-req])

  (request-work!  [this commitment-id job-filter agent])
  (heartbeat!     [this commitment-id])
  (insert-result! [this commitment-id result]))

(defn recursively-add-dependencies
  "This is terribly inefficient"
  [api job]
  (when job
    (assoc job :dependencies (doall (map (partial recursively-add-dependencies api)
                                         (get-jobs api {:dependency_of_job_id (job :job_id)}))))))
(defn get-job [api job-id]
  (doall (recursively-add-dependencies api (first (get-jobs api {:job_id job-id})))))

(defn merge-dependencies [contract api]
  (when contract
    (assoc contract :dependencies (get-jobs api {:dependency_of_job_id (contract :job_id)}))))

(defn get-contract [api params]
              (cond-> (first (get-contracts api params))
                      (params :with-dependencies) (merge-dependencies api)))

(defn new-job! [api agent-details job]
  (let [jobs           (concat [(dissoc job :dependencies)] (flattened-dependencies job))
        parent-job-ids (set (filter (comp not nil?) (map :parent_job_id jobs)))
        leaf-jobs (filter #(not (parent-job-ids (:job_id %))) jobs)]

    (insert-jobs! api jobs agent-details)

    (doseq [leaf-job leaf-jobs]
      (new-contract! api (contract-req (leaf-job :job_id))))

    (get-job api (job :job_id))))

(defn dependency-outcomes
  "This is incredibly inefficient"
  [api job-id]
  (assert (instance? UUID job-id) (str "job-id should be a UUID but was" (ppstr job-id)))
  (reduce (fn [outcomes dependency]
            (cons (dependency :outcome) outcomes))
          #{}
          (get-jobs api (dependencies-of job-id))))

(defn complete-work!
  [api commitment-id result]
  (let [contract (get-contract api {:commitment_id commitment-id})
        job-id (:job_id contract)
        agent-details (:agent result)]

    (assert contract (str "Could not find commitment '" commitment-id "'"))

    (case (contract :outcome)
      :running
      (do
        (insert-result! api commitment-id result)

        (case (:outcome result)
          :success
          (doseq [parent-job (get-jobs api (depends-on contract))]
            (let [dependency-outcomes (dependency-outcomes api (parent-job :job_id))]
              (when (every? #(= :success %)  dependency-outcomes)
                (new-contract! api (contract-req (parent-job :job_id))))))

          :more-work
          (doseq [job (flattened-dependencies {:job_id       job-id
                                               :dependencies (result :dependencies)})]
            (new-job! api agent-details job))

          :try-later
          (new-contract! api (contract-req job-id (result :contract_due)))

          :error
          nil

          :cancelled
          nil))

      :cancelled
      nil

      (throw (IllegalStateException. (str "Contract in state '" (name (contract :outcome)) "' is not expecting a result. Contract\n" (ppstr contract) "\nResult:\n" (ppstr result)))))

    nil))


(defn pause-job! [api job-id agent-details]
            (let [job (get-job api job-id)]
              (when (= :waiting (:outcome job))
                (let [commitment (request-work! api (uuid) {:job_id job-id} agent-details)]
                  (complete-work! api (:commitment_id commitment) (cancelled))))

              (when (= :running (:outcome job))
                (complete-work! api (:commitment_id job) (cancelled))))

            (doseq [dependency  (get-jobs api {:dependency_of_job_id job-id})]
              (pause-job! api (dependency :job_id) agent-details)))
