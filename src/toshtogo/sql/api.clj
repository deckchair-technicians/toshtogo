(ns toshtogo.sql.api
  (:require [clojure.java.jdbc :as sql]
            [clj-time.core :refer [now]]
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

        (doseq [dependency (map (partial expand-dependency job-agent)
                                dependencies)]
          (put-job! this dependency)
          (tsql/insert! cnxn :job_dependencies (dependency-record job dependency)))

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

    (new-contract! [this job-id]
      (let [last-contract       (get-contract this {:job_id job-id :latest_contract true})
            new-contract-number (if last-contract (inc (last-contract :contract_number)) 1)
            last-contract-outcome (:outcome last-contract)]
        (case last-contract-outcome
          :waiting
          (throw (unfinished-contract job-id))
          :success
          (throw (job-finished job-id))
          (tsql/insert! cnxn :contracts (contract-record job-id new-contract-number)))))

    (request-work! [this commitment-id tags agent-details]
      (when-let [contract  (get-contract
                            this
                            {:outcome       :waiting
                             :tags          tags
                             :order-by [:contract_created]})]
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
        (let [outcome (result :outcome)
              job-id  (contract :job_id)]

          (tsql/insert! cnxn
                        :commitment_outcomes
                        {:outcome_id        commitment-id
                         :error             (result :error)
                         :contract_finished (now)
                         :outcome           outcome})

          (when (= :success outcome)
            (tsql/insert! cnxn
                          :job_results
                          (outcome-record contract result)))

          (on-contract-completed! this (get-contract this {:commitment_id commitment-id}))
          nil)

        (throw (NullPointerException. (str "Could not find commitment '" commitment-id "'")))))))

(defn handle-new-job! [api job]
  (when-not (job :dependencies)
    (new-contract! api (job :job_id))))

(defn handle-contract-completion! [api contract]
  (when (= :success (contract :outcome))
    (doseq [parent-job (get-jobs api (depends-on contract))]
      (let [dependency-outcomes (dependency-outcomes api parent-job)]
        (when (every? #(= :success %)  dependency-outcomes)
          (new-contract! api (parent-job :job_id)))))))

(defn sql-api [cnxn agents]
  (SqlApi cnxn handle-new-job! handle-contract-completion! agents))
