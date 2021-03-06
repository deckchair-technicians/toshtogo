(ns toshtogo.server.persistence.sql-persistence
  (:require [clojure.java.jdbc :as sql]

            [clojure.pprint :refer [pprint]]

            [clj-time
             [core :refer [now]]
             [format :refer [parse]]]

            [cheshire.core :as json]

            [flatland.useful.map :as mp]

            [toshtogo.util
             [core :refer [uuid debug ensure-seq ppstr]]]

            [toshtogo.server.persistence
             [agents-helper :refer [agent-record select-agent]]
             [protocol :refer :all]
             [sql-jobs-helper :refer :all]
             [sql-contracts-helper :refer :all]]

            [toshtogo.server
             [validation :refer [JobRecord DependencyRecord UniqueConstraintException
                                 validated matches-schema?]]]

            [toshtogo.server.util
             [sql :as ttsql]]

            [toshtogo.util.hsql :as hsql])

  (:import [clojure.lang ExceptionInfo]))

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

    (insert-graph! [this graph-id root-job-id]
      (ttsql/insert! cnxn :job_graphs {:graph_id graph-id :root_job_id root-job-id}))

    (insert-jobs! [this jobs]
      (apply ttsql/insert! cnxn :jobs (validated jobs [JobRecord])))

    (insert-contract! [this job-id contract-ordinal contract-due]
      (let [contract (contract-record job-id contract-ordinal contract-due)]
        (ttsql/insert! cnxn :contracts contract)
        (ttsql/update! cnxn :jobs
                       {:latest_contract (:contract_id contract)}
                       ["job_id = ?" job-id])))

    (insert-commitment!
      [this commitment-id contract agent-details]
      (assert contract "no contract")
      (assert commitment-id "no commitment-id")

      (ttsql/execute! cnxn ["savepoint before_insert"])

      (try
        (ttsql/insert! cnxn :agent_commitments
          (commitment-record
            commitment-id
            (:contract_id contract)
            (agent! this agent-details)))
        true

        (catch ExceptionInfo e
          (if (matches-schema? UniqueConstraintException (ex-data e))
            (do (ttsql/execute! cnxn ["rollback to before_insert"]) false)
            (throw e)))))

    (upsert-heartbeat! [this commitment-id]
      (let [heartbeat-time (now)]
        (ttsql/update! cnxn :agent_commitments
                      {:last_heartbeat heartbeat-time}
                      ["commitment_id = ?" commitment-id]))
      (let [contract  (get-contract this {:commitment_id commitment-id})]
        (if (= :cancelled (contract :outcome))
          {:instruction :cancel}
          {:instruction :continue})))

    (insert-result! [this commitment-id result agent-details]
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
        (if (or (:page params)
                (:page_size params))
          (-> (hsql/page cnxn (job-query params)
                         :count-sql-map (job-query params)
                         :page (:page params 1)
                         :page-size (:page_size params 25))
              (mp/update :data normalise-job-rows))
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
