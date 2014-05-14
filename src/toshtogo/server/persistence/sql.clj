(ns toshtogo.server.persistence.sql
  (:require [clojure.java.jdbc :as sql]
            [clojure.pprint :refer [pprint]]
            [clj-time.format :refer [parse]]
            [clj-time.core :refer [now]]
            [cheshire.core :as json]
            [flatland.useful.map :refer [update update-each]]
            [toshtogo.util.core :refer [uuid debug ensure-seq ppstr]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.api :refer [get-job get-contract]]
            [toshtogo.server.persistence.sql-jobs-helper :refer :all]
            [toshtogo.server.persistence.sql-contracts-helper :refer :all]
            [toshtogo.server.agents.protocol :refer [agent!]]
            [toshtogo.util.sql :as ttsql]
            [toshtogo.util.hsql :as hsql]))

(defn sql-persistence [cnxn agents]
  (reify Persistence
    (insert-dependency! [this parent-job-id child-job-id]
      (ttsql/insert! cnxn :job_dependencies {:dependency_id (uuid)
                                            :parent_job_id parent-job-id
                                            :child_job_id  child-job-id}))

    (insert-tree! [this tree-id root-job-id]
      (ttsql/insert! cnxn :job_trees {:tree_id tree-id :root_job_id root-job-id}))

    (insert-tree-membership! [this tree-id job-id]
      (when-not (is-tree-member cnxn tree-id job-id)
        (ttsql/insert! cnxn :job_tree_members {:membership_tree_id tree-id
                                               :tree_job_id        job-id})))

    (insert-jobs! [this jobs agent-details tree-id]
      (doseq [job jobs]
        (let [job-id          (job :job_id)
              job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
              job-agent       (agent! agents agent-details)
              job-row         (job-record tree-id
                                          job-id
                                          (job :job_name)
                                          (job :job_type)
                                          (job-agent :agent_id)
                                          (job :request_body)
                                          (job :notes)
                                          (job :fungibility_group_id))
              parent-job-id   (job :parent_job_id)]

          (ttsql/insert! cnxn :jobs job-row)

          (when (not (empty? job-tag-records))
            (apply ttsql/insert! cnxn :job_tags job-tag-records))

          (when parent-job-id
            (insert-dependency! this parent-job-id job-id))

          (doseq [dependency-id (:existing_job_dependencies job)]
            (insert-dependency! this job-id dependency-id))

          (get-job this job-id))))

    (insert-contract! [this job-id contract-ordinal contract-due]
      (ttsql/insert! cnxn :contracts (contract-record job-id contract-ordinal contract-due)))

    (insert-commitment!
      [this commitment-id contract-id agent-details]
      (assert contract-id "no contract-id")
      (assert commitment-id "no commitment-id")

      (ttsql/insert!
        cnxn
        :agent_commitments
        (commitment-record
          commitment-id
          contract-id
          (agent! agents agent-details))))

    (upsert-heartbeat! [this commitment-id]
      (let [heartbeat-time (now)]
        (ttsql/update! cnxn :agent_commitments
                      {:last_heartbeat heartbeat-time}
                      ["commitment_id = ?" commitment-id]))
      (let [contract  (get-contract this {:commitment_id commitment-id})]
        (if (= :cancelled (contract :outcome))
          {:instruction :cancel}
          {:instruction :continue})))

    (insert-result! [this commitment-id result]
      (assert commitment-id "no commitment-id")
      (ttsql/insert! cnxn
                    :commitment_outcomes
                    {:outcome_id        commitment-id
                     :error             (result :error)
                     :contract_finished (now)
                     :outcome           (result :outcome)})

      (cond
        (= :success (result :outcome))
        (ttsql/insert! cnxn
                      :job_results
                      (outcome-record (:job_id (get-contract this {:commitment_id commitment-id})) result))

        (#{:more-work :try-later :error :cancelled} (result :outcome))
        nil

        :else (throw (IllegalStateException. (str "Unknown outcome " (result :outcome) " in result " (ppstr result))))))

    (get-jobs [this params]
      (let [params (update params :order-by #(concat (ensure-seq %) [:jobs.job_id]))]
        (if (:page params)
          (-> (hsql/page cnxn (job-query params)
                         :count-sql-map (job-query (dissoc params :get_tags))
                         :page (:page params 1)
                         :page-size (:page-size params))
              (update :data normalise-job-rows))
          (normalise-job-rows
            (hsql/query
              cnxn
              (job-query params))))))

    (get-contracts [this params]
      (map
       normalise-contract
       (hsql/query
              cnxn
              (contract-query (assoc params :has_contract true)))))

    (get-job-types [this]
      (map :job_type (hsql/query cnxn job-types-query)))))