(ns toshtogo.server.api.sql
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now minus seconds]]
            [clj-time.format :refer [parse]]
            [cheshire.core :as json]
            [flatland.useful.map :refer [update update-each]]
            [toshtogo.util.core :refer [uuid debug as-coll]]
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

(defn- put-dependencies! [api cnxn job-id dependencies agent]
  (doseq [dependency (map (partial expand-dependency agent)
                          dependencies)]
    (put-job! api dependency)
    (tsql/insert! cnxn :job_dependencies (dependency-record job-id dependency))))

(defn- recursively-add-dependencies
  "This should really be a postgres recursive CTE- this is terribly inefficient"
  [api job]
  (assoc job :dependencies (doall (map (partial recursively-add-dependencies api)
                                       (get-jobs api {:dependency_of_job_id (job :job_id)})))))

(defn SqlApi [cnxn on-new-job! on-contract-completed! agents]
  (reify Toshtogo
    (put-job! [this job]
      (let [job-id          (job :job_id)
            job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
            job-agent       (agent! agents (job :agent))
            job-row         (job-record job-id (job :job_type) (job-agent :agent_id) (job :request_body) (job :notes))
            dependencies    (job :dependencies)]

        (tsql/insert! cnxn :jobs job-row)
        (when (not (empty? job-tag-records))
          (apply tsql/insert! cnxn :job_tags job-tag-records))

        (put-dependencies! this cnxn job-id dependencies job-agent)

        (on-new-job! this job)

        (get-job this job-id)))

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

    (get-job [this job-id]
      (doall (recursively-add-dependencies this (first (get-jobs this {:job_id job-id})))))

    (pause-job! [this job-id agent-details]
      (let [contract (get-contract this {:job_id job-id})]
        (when (= :waiting (:outcome contract))
          (let [commitment-id (ensure-commitment-id! cnxn agents contract agent-details)]
            (complete-work! this commitment-id (cancelled)))))
      (doseq [dependency  (get-jobs this {:dependency_of_job_id job-id})]
        (pause-job! this (dependency :job_id) agent-details)))

    (get-contracts [this params]
      (map
       normalise-record
       (apply tsql/query
              cnxn
              (tsql/qualify
               contracts-where-fn
               contracts-sql
               params))))

    (get-contract [this params]
      (cond-> (first (get-contracts this params))
              (params :with-dependencies) (merge-dependencies this)))

    (new-contract! [this contract]
      (let [job-id                (contract :job_id)
            contract-due          (:contract_due contract (minus (now) (seconds 5)))
            last-contract         (get-contract this {:job_id job-id :latest_contract true})
            new-contract-number   (if last-contract (inc (last-contract :contract_number)) 1)
            last-contract-outcome (:outcome last-contract)]

        (case last-contract-outcome
          :waiting
          (throw (unfinished-contract job-id))
          :success
          (throw (job-finished job-id))
          (tsql/insert! cnxn :contracts
                        (contract-record job-id new-contract-number contract-due)))))

    (request-work! [this commitment-id job-type agent-details]
      (when-let [contract  (get-contract
                            this
                            {:job_type           job-type
                             :ready_for_work true
                             :order-by       [:contract_created]})]
        (insert-commitment! cnxn agents commitment-id contract agent-details))

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

    (complete-work! [this commitment-id result]
      (if-let [contract (get-contract this {:commitment_id commitment-id})]
        (let [outcome       (result :outcome)
              job-id        (contract :job_id)
              agent-details (result :agent)]
          (when (not= :cancelled (contract :outcome))
            (tsql/insert! cnxn
                          :commitment_outcomes
                          {:outcome_id        commitment-id
                           :error             (result :error)
                           :contract_finished (now)
                           :outcome           outcome})

            (case outcome
              :success
              (tsql/insert! cnxn
                            :job_results
                            (outcome-record contract result))
              :more-work
              (put-dependencies! this cnxn job-id (result :dependencies) agent-details)

              :try-later
              (new-contract! this (contract-req job-id (result :contract_due)))

              :error
              nil

              :cancelled
              nil))

          (on-contract-completed! this (get-contract this {:commitment_id commitment-id}))
          nil)

        (throw (NullPointerException. (str "Could not find commitment '" commitment-id "'")))))))

(defn sql-api [cnxn agents]
  (SqlApi
   cnxn
   handle-new-job!
   (partial handle-contract-completion! cnxn)
   agents))
