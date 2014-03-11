(ns toshtogo.server.api.sql
  (:require [clojure.java.jdbc :as sql]
            [clojure.pprint :refer [pprint]]
            [clj-time.core :refer [now minus seconds]]
            [clj-time.format :refer [parse]]
            [cheshire.core :as json]
            [flatland.useful.map :refer [update update-each]]
            [toshtogo.util.core :refer [uuid debug as-coll ppstr]]
            [toshtogo.server.api.protocol :refer :all]
            [toshtogo.server.api.sql-jobs-helper :refer :all]
            [toshtogo.server.api.sql-contracts-helper :refer :all]
            [toshtogo.server.agents.protocol :refer [agent!]]
            [toshtogo.util.sql :as tsql]))

(defn unfinished-contract [job-id]
  (IllegalStateException.
   (str "Job " job-id " has an unfinished contract. Can't create a new one.")))

(defn job-finished [job-id]
  (IllegalStateException.
   (str "Job " job-id " has been completed. Can't create further contracts")))

(defn insert-dependency! [cnxn parent-job-id child-job-id]
  (tsql/insert! cnxn :job_dependencies {:dependency_id (uuid)
                                        :parent_job_id parent-job-id
                                        :child_job_id  child-job-id}))

(defn SqlApi [cnxn agents]
  (reify Toshtogo
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
            (insert-dependency! cnxn parent-job-id job-id))

          (get-job this job-id))))

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
               params))))

    (new-contract! [this contract-req]
      (let [job-id                (contract-req :job_id)
            contract-due          (:contract_due contract-req (minus (now) (seconds 5)))
            last-contract         (get-contract this {:job_id job-id :latest_contract true})
            new-contract-number   (if last-contract (inc (last-contract :contract_number)) 1)
            last-contract-outcome (:outcome last-contract)]

        (case last-contract-outcome
          :waiting
          (throw (unfinished-contract job-id))
          :success
          (throw (job-finished job-id))
          (let [contract (contract-record job-id new-contract-number contract-due)]
            (tsql/insert! cnxn :contracts contract)
            contract))))

    (request-work! [this commitment-id job-filter agent-details]
      (when-let [contract  (get-contract
                            this
                            (assoc job-filter
                              :ready_for_work true
                              :order-by [:contract_created]))]
        (insert-commitment! cnxn agents commitment-id (contract :contract_id) agent-details))

      (get-contract this {:commitment_id     commitment-id
                          :with-dependencies true}))

    (heartbeat! [this commitment-id]
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

        :else (throw (IllegalStateException. (str "Unknown outcome " (result :outcome) " in result " (ppstr result))))))))

(defn sql-api [cnxn agents]
  (SqlApi
   cnxn
   agents))
