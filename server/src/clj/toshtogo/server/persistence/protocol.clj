(ns toshtogo.server.persistence.protocol)

(defn contract-req
  ([job-id]
     {:job_id job-id})
  ([job-id contract-due]
     (assoc (contract-req job-id) :contract_due contract-due)))

(defn depends-on [contract]
  {:depends_on_job_id (contract :job_id) :order-by :job_created})

(defn dependencies-of [job-id]
  {:dependency_of_job_id job-id :order-by :job_created})

(defprotocol Persistence
  (agent! [this agent-details])
  (insert-graph! [this graph-id root-job-id])
  (insert-jobs! [this jobs])
  (insert-dependency! [this dependency-record])

  (insert-contract!   [this job-id contract-number contract-due])
  (insert-commitment! [this commitment-id contract agent-details])
  (upsert-heartbeat!  [this commitment-id])
  (insert-result!     [this commitment-id result agent-details])

  (get-jobs             [this params])
  (get-dependency-links [this params])
  (get-contracts        [this params])

  (get-job-types [this]))

(defn- assoc-dependencies
  [persistence job]
  (when job
    (assoc job :dependencies (get-jobs persistence {:dependency_of_job_id (job :job_id)}))))

(defn- merge-dependencies [contract persistence]
  (when contract
    (assoc contract :dependencies (get-jobs persistence {:dependency_of_job_id (contract :job_id)}))))

(defn get-graph [persistence graph-id]
  (let [params {:graph_id graph-id
                :fields  [:jobs.job_id :jobs.job_name :jobs.job_type :outcome]}]
    {:root_job (first (get-jobs persistence {:root_of_graph_id graph-id
                                             :fields          [:jobs.job_id]}))
     :jobs     (get-jobs persistence params)
     :links    (get-dependency-links persistence params)}))

(defn get-job
  [persistence job-id]
  (assoc-dependencies persistence (first (get-jobs persistence {:job_id job-id}))))

(defn get-contract
  [persistence params]
  (cond-> (first (get-contracts persistence params))
          (params :with-dependencies) (merge-dependencies persistence)))
