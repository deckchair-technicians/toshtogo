(ns toshtogo.server.persistence.sql-jobs-helper
  (:require [flatland.useful.map :as mp]
            [clj-time.core :refer [now]]

            [honeysql.helpers :refer [select from where]]

            [toshtogo.server.persistence
             [sql-contracts-helper :refer [job-query]]]

            [toshtogo.util
             [deterministic-representation :refer [database-representation]]
             [hsql :as hsql]
             [json :as json]]))

(defn job-outcome [job]
  (if (:outcome job)
    (:outcome job)
    (if (:contract_claimed job)
      :running
      (if (:contract_id job)
          :waiting
          :no-contract))))

(defn fix-job-outcome [job]
  (if (contains? job :outcome)
    (assoc job :outcome (job-outcome job))
    job))

(defn normalise-job [job]
  (-> job
      (dissoc :job_id_2 :job_id_3 :job_id_4 :commitment_contract :outcome_id)
      (mp/update-each [:outcome :job_type] keyword)
      (fix-job-outcome)
      (mp/update-each [:request_body :result_body] #(json/decode %))))

(defn normalise-job-rows
  [job-rows]
  (map normalise-job job-rows))

(defn commitment-record [commitment-id contract-id agent]
  {:commitment_id       commitment-id
   :commitment_contract contract-id
   :commitment_agent    (agent :agent_id)
   :contract_claimed    (now)})

(defn is-graph-member [cnxn graph-id job-id]
  (not (empty? (hsql/query cnxn (-> (select :*)
                                    (from :job_dependencies)
                                    (where [:and
                                            [:= :dependency_graph_id graph-id]
                                            [:or
                                                  [:= :parent_job_id job-id]
                                                  [:= :child_job_id job-id]]]))))))

(defn links-query [params]
  (-> (select :links.parent_job_id :links.child_job_id)
      (from [:job_dependencies :links])
      (where [:or
              [:in :links.parent_job_id (-> (job-query params)
                                            (select [:jobs.job_id]))]
              [:in :links.child_job_id (-> (job-query params)
                                            (select [:jobs.job_id]))]])))

(defn graph-query [graph-id]
  (-> (select :*)
      (from :job_graphs)
      (where [:= :job_graphs.graph_id graph-id])))
