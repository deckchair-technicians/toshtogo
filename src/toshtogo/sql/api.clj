(ns toshtogo.sql.api
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now minus seconds]]
            [clj-time.format :refer [parse]]
            [cheshire.core :as json]
            [flatland.useful.map :refer [update update-each]]
            [toshtogo.util.core :refer [uuid debug as-coll]]
            [toshtogo.api :refer :all]
            [toshtogo.sql.jobs-helper :refer :all]
            [toshtogo.sql.contracts-helper :refer :all]
            [toshtogo.agents :refer [agent!]]
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

(defn SqlApi [cnxn on-new-job! on-contract-completed! agents]
  (reify Toshtogo
    (put-job! [this job]
      (let [job-id          (job :job_id)
            job-tag-records (map (fn [tag] {:job_id job-id :tag tag}) (job :tags))
            job-agent       (agent! agents (job :agent))
            job-row         (job-record job-id (job-agent :agent_id) (job :request_body))
            dependencies    (job :dependencies)]

        (tsql/insert! cnxn :jobs job-row)
        (apply tsql/insert! cnxn :job_tags job-tag-records)

        (put-dependencies! this cnxn job-id dependencies job-agent)

        (on-new-job! this job)

        (get-job this job-id)))

    (get-jobs [this params]
      (map from-sql
           (partition-by
            :job_id
            (apply tsql/query
                   cnxn
                   (tsql/qualify jobs-where-fn
                                 job-sql
                                 (update params :order-by #(concat (as-coll %) [:jobs.job_id])))))))

    (get-job [this job-id]
      (first (get-jobs this {:job_id job-id})))

    (get-contracts [this params]
      (map
       normalise-record
       (apply tsql/query
              cnxn
              (tsql/qualify
               contracts-where-fn
               (contracts-sql params)
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

    (request-work! [this commitment-id tags agent-details]
      (when-let [contract  (get-contract
                            this
                            {:tags           tags
                             :ready_for_work true
                             :order-by       [:contract_created]})]
        (tsql/insert!
         cnxn
         :agent_commitments
         (commitment-record commitment-id
                            contract
                            (agent! agents agent-details))))

      (get-contract this {:commitment_id     commitment-id
                          :return-jobs       true
                          :with-dependencies true}))

    (complete-work! [this commitment-id result]
      (if-let [contract (get-contract this {:commitment_id commitment-id})]
        (let [outcome       (result :outcome)
              job-id        (contract :job_id)
              agent-details (result :agent)]

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
            nil)

          (on-contract-completed! this (get-contract this {:commitment_id commitment-id}))
          nil)

        (throw (NullPointerException. (str "Could not find commitment '" commitment-id "'")))))))

(defn sql-api [cnxn agents]
  (SqlApi
   cnxn
   handle-new-job!
   (partial handle-contract-completion! cnxn)
   agents))
