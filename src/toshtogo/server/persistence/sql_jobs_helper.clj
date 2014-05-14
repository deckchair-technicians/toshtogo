(ns toshtogo.server.persistence.sql-jobs-helper
  (:require [flatland.useful.map :as mp]
            [pallet.map-merge :refer [merge-keys]]
            [clj-time.core :refer [now]]
            [clj-time.coerce :as tc]
            [cheshire.core :as json]
            [honeysql.helpers :refer :all]
            [toshtogo.server.agents.protocol :refer [agent!]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.util.core :refer [uuid debug]]
            [toshtogo.util.deterministic-representation :refer [deterministic-representation]]
            [toshtogo.util.hsql :as hsql])
  (:import [toshtogo.util OptimisticLockingException]))

(defn job-record [tree-id job-id job-name job-type agent-id body notes fungibility-group-id]
  {:home_tree_id         tree-id
   :job_id               job-id
   :job_name             job-name
   :job_type             job-type
   :requesting_agent     agent-id
   :job_created          (now)
   :request_body         (json/generate-string (deterministic-representation body))
   :fungibility_group_id fungibility-group-id
   :notes                notes})

(defn collect-tags [job row]
  (if job
    (-> job
        (mp/update :tags #(conj % (row :tag))))
    (if (row :tag)
      (assoc row :tags #{(row :tag)})
      row)))

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
      (dissoc :tag)
      (dissoc :job_id_2 :job_id_3 :job_id_4 :commitment_contract :outcome_id)
      (mp/update :outcome keyword)
      (fix-job-outcome)
      (mp/update-each [:request_body :result_body] #(json/parse-string % keyword))))

(defn fold-in-tags [job-with-tags]
  (reduce collect-tags
          nil
          job-with-tags))

(defn normalise-job-rows
  "job-rows might include joins in to the tags table"
  [job-rows]
  (map (comp normalise-job fold-in-tags)
       (partition-by :job_id job-rows)))

(defn commitment-record [commitment-id contract-id agent]
  {:commitment_id       commitment-id
   :commitment_contract contract-id
   :commitment_agent    (agent :agent_id)
   :contract_claimed    (now)})

(defn is-tree-member [cnxn tree-id job-id]
  (not (empty? (hsql/query cnxn (-> (select :*)
                                    (from :job_tree_members)
                                    (where [:and
                                            [:= :membership_tree_id tree-id]
                                            [:= :tree_job_id job-id]]))))))