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
            [toshtogo.server.persistence.agents-helper :refer :all]
            [toshtogo.server.persistence.sql-jobs-helper :refer :all]
            [toshtogo.server.persistence.sql-contracts-helper :refer :all]
            [toshtogo.server.validation :refer [JobRecord DependencyRecord validated]]
            [toshtogo.util.sql :as ttsql]
            [toshtogo.util.hsql :as hsql]))

(defn sql-persistence [cnxn]
  (reify Persistence
    (agent! [this agent-details]
      (if-let [agent (first (hsql/query cnxn select-agent :params agent-details))]
        agent
        (let [agent-record (agent-record agent-details)]
          (ttsql/insert! cnxn :agents agent-record)
          agent-record)))

    (insert-dependency! [this dependency-record]
      (ttsql/insert! cnxn :job_dependencies (-> dependency-record
                                              (assoc :dependency_id (uuid))
                                              (validated DependencyRecord))))

    (insert-tree! [this tree-id root-job-id]
      (ttsql/insert! cnxn :job_trees {:tree_id tree-id :root_job_id root-job-id}))

    (insert-jobs! [this jobs]
      (doseq [job (map (fn [job]
                         (-> job
                             to-job-record
                             (validated JobRecord)))
                       jobs)]
        (let [job-id          (job :job_id)
              job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))]

          (ttsql/insert! cnxn :jobs (dissoc job :tags))

          (when (not (empty? job-tag-records))
            (apply ttsql/insert! cnxn :job_tags job-tag-records))

          (get-job this job-id))))

    (insert-contract! [this job-id contract-ordinal contract-due]
      (let [contract (contract-record job-id contract-ordinal contract-due)]
        (ttsql/insert! cnxn :contracts contract)
        (ttsql/update! cnxn :jobs
                       {:latest_contract (:contract_id contract)}
                       ["job_id = ?" job-id])))

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
          (agent! this agent-details))))

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
      (let [order-by (ensure-seq (:order-by params))
            order-by (if (empty? order-by) [:job_created] order-by)
            params   (assoc params :order-by order-by)]
        (if (:page params)
          (-> (hsql/page cnxn (job-query params)
                         :count-sql-map (job-query (dissoc params :get_tags))
                         :page (:page params 1)
                         :page-size (:page_size params))
              (update :data normalise-job-rows))
          (normalise-job-rows
            (hsql/query
              cnxn
              (job-query params))))))

    (get-dependency-links [this params]
      (hsql/query
        cnxn
        (links-query params)))

    (get-contracts [this params]
      (map
       normalise-contract
       (hsql/query
              cnxn
              (contract-query (assoc params :has_contract true)))))

    (get-job-types [this]
      (map :job_type (hsql/query cnxn job-types-query)))))
