(ns toshtogo.server.persistence.sql
  (:require [clojure.java.jdbc :as sql]
            [clojure.pprint :refer [pprint]]
            [clj-time.format :refer [parse]]
            [clj-time.core :refer [now]]
            [cheshire.core :as json]
            [flatland.useful.map :refer [update update-each]]
            [toshtogo.util.core :refer [uuid debug as-coll ppstr]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.api :refer [get-job get-contract]]
            [toshtogo.server.persistence.sql-jobs-helper :refer :all]
            [toshtogo.server.persistence.sql-contracts-helper :refer :all]
            [toshtogo.server.agents.protocol :refer [agent!]]
            [toshtogo.util.sql :as tsql]))

(defn sql-api [cnxn agents]
  (reify Persistence
    (insert-jobs! [this jobs agent-details]
      (doseq [job jobs]
        (let [job-id (job :job_id)
              job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
              job-agent (agent! agents agent-details)
              job-row (job-record job-id (job :job_type) (job-agent :agent_id) (job :request_body) (job :notes))
              parent-job-id (job :parent_job_id)]

          (tsql/insert! cnxn :jobs job-row)

          (when (not (empty? job-tag-records))
            (apply tsql/insert! cnxn :job_tags job-tag-records))

          (when parent-job-id
            (tsql/insert! cnxn :job_dependencies {:dependency_id (uuid)
                                                  :parent_job_id parent-job-id
                                                  :child_job_id  job-id}))

          (get-job this job-id))))

    (insert-contract! [this job-id contract-ordinal contract-due]
      (tsql/insert! cnxn :contracts (contract-record job-id contract-ordinal contract-due)))

    (insert-commitment!
      [this commitment-id contract-id agent-details]
      (assert contract-id "no contract-id")
      (assert commitment-id "no commitment-id")

      (tsql/insert!
        cnxn
        :agent_commitments
        (commitment-record
          commitment-id
          contract-id
          (agent! agents agent-details))))

    (upsert-heartbeat! [this commitment-id]
      (let [heartbeat-time (now)]
        (tsql/update! cnxn :agent_commitments
                      {:last_heartbeat heartbeat-time}
                      ["commitment_id = ?" commitment-id]))
      (let [contract  (get-contract this {:commitment_id commitment-id})]
        (if (= :cancelled (contract :outcome))
          {:instruction :cancel}
          {:instruction :continue})))

    (insert-result! [this commitment-id result]
      (assert commitment-id "no commitment-id")
      (tsql/insert! cnxn
                    :commitment_outcomes
                    {:outcome_id        commitment-id
                     :error             (result :error)
                     :contract_finished (now)
                     :outcome           (result :outcome)})

      (cond
        (= :success (result :outcome))
        (let [contract (get-contract this {:commitment_id commitment-id})]
          (tsql/insert! cnxn
                        :job_results
                        (outcome-record (:job_id contract) result))

          (doseq [parent-job (get-jobs this (depends-on contract))]
            (incremement-succeeded-dependency-count! cnxn (parent-job :job_id))))

        (#{:more-work :try-later :error :cancelled} (result :outcome))
        nil

        :else (throw (IllegalStateException. (str "Unknown outcome " (result :outcome) " in result " (ppstr result))))))

    (get-jobs [this params]
      (let [params (update params :order-by #(concat (as-coll %) [:jobs.job_id]))]
        (if (:page params)
          (update
            (tsql/page cnxn jobs-where-fn job-sql params :count-params (assoc params :get-tags false))
            :data normalise-job-rows)
          (normalise-job-rows
            (tsql/query
              cnxn
              (tsql/qualify jobs-where-fn (job-sql (assoc params :get-tags true)) params))))))

    (get-contracts [this params]
      (map
       normalise-record
       (apply tsql/query
              cnxn
              (tsql/qualify
               contracts-where-fn
               contracts-sql
               params))))))