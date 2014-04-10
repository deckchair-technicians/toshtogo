(ns toshtogo.server.persistence.sql-contracts-helper
  (:require [flatland.useful.map :as mp]
            [cheshire.core :as json]
            [clj-time.core :refer [now]]
            [clojure.string :as str]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.agents.protocol :refer [agent!]]
            [honeysql.helpers :refer :all]
            [toshtogo.util.hsql :as hsql]
            [toshtogo.util.hashing :refer [murmur-uuid!]]
            [toshtogo.util.core :refer [uuid debug ensure-seq safe-name]]))

(defn contract-record [job-id contract-number contract-due]
  {:contract_id      (uuid)
   :job_id           job-id
   :contract_number  contract-number
   :contract_created (now)
   :contract_due     contract-due})

(defn outcome-record [job-id result]
  {:job_id      job-id
   :result_body (json/generate-string (result :result))})

(def base-query
  (-> (select :*)
      (from :jobs)
      (merge-left-join :contracts
                 [:= :jobs.job_id :contracts.job_id])
      (merge-left-join :agent_commitments
                 [:= :contracts.contract_id :agent_commitments.commitment_contract])
      (merge-left-join :commitment_outcomes
                 [:= :agent_commitments.commitment_id :commitment_outcomes.outcome_id])
      (merge-left-join :job_results
                 [:= :jobs.job_id :job_results.job_id])))

(def job-max-contract-number (-> (select :%max.contract_number)
                      (from [:contracts :c])
                      (where [:= :c.job_id :contracts.job_id])))

(def unfinished-dependency-count
  (-> (select :%count.*)
      (from [:job_dependencies :U_job_dependencies])
      (merge-left-join [:job_results :U_job_results]
                       [:= :U_job_dependencies.child_job_id :U_job_results.job_id])
      (merge-where [:= :jobs.job_id :U_job_dependencies.parent_job_id])
      (merge-where [:= nil :U_job_results.job_id])))

(def is-waiting
  [:and
   [:= :outcome nil]
   [:= :commitment_id nil]])

(defn outcome-expression [outcome]
  (case outcome
    :waiting is-waiting
    :running [:and [:= :outcome nil]
              [:not= :commitment_id nil]]
    [:= :outcome (name outcome)]))

(defn max-due-time [query v]
  (merge-where query [:<= :contracts.contract_due v]))

(defn contract-query [params]
  (reduce
   (fn [query [k v]]
     (case k
       :ready_for_work
       (if v
         (-> query
             (merge-where [:= 0 unfinished-dependency-count])
             (merge-where is-waiting)
             (max-due-time (now)))
         (throw (UnsupportedOperationException. ":ready_for_work can only be true")))

       :outcome
       (merge-where query (concat [:or] (map outcome-expression (ensure-seq v))))

       :has_contract
       (if v
         (merge-where query [:not= :contracts.contract_id nil])
         (merge-where query [:=    :contracts.contract_id nil]))

       :job_type
       (merge-where query [:in :job_type (mapv name (ensure-seq v))])

       :tags
       (merge-where query [:in :jobs.job_id (-> (select :j.job_id)
                                                (modifiers :distinct)
                                                (from [:jobs :j])
                                                (join [:job_tags :t] [:= :j.job_id :t.job_id])
                                                (where [:in :t.tag (vec (map safe-name v))]))])

       :commitment_id
       (merge-where query [:= :agent_commitments.commitment_id v])

       :job_id
       (merge-where query [:= :jobs.job_id v])

       :max_due_time
       (max-due-time query v)

       :latest_contract
       (if v
         (merge-where query [:or [:= :contract_id nil]
                             [:= :contract_number job-max-contract-number]])
         (merge-where query [:or [:contract_id nil]
                             [:not= :contract_number job-max-contract-number]]))

       :depends_on_job_id
       (merge-where query [:in :jobs.job_id (-> (select :parent_job_id)
                                                (from :job_dependencies)
                                                (where [:= :child_job_id v]))])
       :dependency_of_job_id
       (merge-where query [:in :jobs.job_id (-> (select :child_job_id)
                                                (from :job_dependencies)
                                                (where [:= :parent_job_id v]))])

       :request_body
       (merge-where query [:= :request_hash (murmur-uuid! v)])

       :fungibility_group_id
       (merge-where query [:= :jobs.fungibility_group_id v])

       :get_tags
       (merge-left-join query :job_tags
                        [:= :jobs.job_id :job_tags.job_id])

       :order-by
       (if v
         (apply order-by query (ensure-seq v))
         query)

       :page
       query

       :page-size
       query))
   base-query
   (dissoc params :with-dependencies)))

(defn job-query [params]
  (-> params
      (assoc :get_tags true)
      (assoc :latest_contract true)
      contract-query))

(defn contract-outcome [contract]
  (if (:outcome contract)
    (:outcome contract)
    (if (:contract_claimed contract)
      :running
      :waiting)))

(defn fix-contract-outcome [contract]
  (assoc contract :outcome (contract-outcome contract)))

(defn normalise-record [contract]
  (-> contract
      (mp/update :outcome keyword)
      fix-contract-outcome
      (mp/update :request_body #(json/parse-string % keyword))))

(def job-types-query (-> (select :job_type)
                         (modifiers :distinct)
                         (from :jobs)
                         (order-by :job_type)))