(ns toshtogo.test.util.sql-test
  (:require [midje.sweet :refer :all]
            [clojure.pprint :refer [pprint]]
            [honeysql.helpers :refer :all]
            [toshtogo.util.sql :refer :all]))

(fact "prefix-alias-tables can take a HoneySql query and alias all references to tables with a prefix"
      (->> (-> (select :*)
              (from :jobs)
              (merge-left-join :contracts
                               [:= :jobs.job_id :contracts.job_id])
              (merge-left-join :agent_commitments
                               [:= :contracts.contract_id :agent_commitments.commitment_contract])
              (merge-left-join :commitment_outcomes
                               [:= :agent_commitments.commitment_id :commitment_outcomes.outcome_id])
              (merge-left-join :job_results
                               [:= :jobs.job_id :job_results.job_id]))
          (prefix-alias-tables "ALIAS_"))
      => (-> (select :*)
             (from [:jobs :ALIAS_jobs])
             (merge-left-join [:contracts :ALIAS_contracts]
                              [:= :ALIAS_jobs.job_id :ALIAS_contracts.job_id])
             (merge-left-join [:agent_commitments :ALIAS_agent_commitments]
                              [:= :ALIAS_contracts.contract_id :ALIAS_agent_commitments.commitment_contract])
             (merge-left-join [:commitment_outcomes :ALIAS_commitment_outcomes]
                              [:= :ALIAS_agent_commitments.commitment_id :ALIAS_commitment_outcomes.outcome_id])
             (merge-left-join [:job_results :ALIAS_job_results]
                              [:= :ALIAS_jobs.job_id :ALIAS_job_results.job_id])))
