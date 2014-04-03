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
          (prefix-alias-tables "abc_"))
      => (-> (select :*)
             (from [:jobs :abc_jobs])
             (merge-left-join [:contracts :abc_contracts]
                              [:= :abc_jobs.job_id :abc_contracts.job_id])
             (merge-left-join [:agent_commitments :abc_agent_commitments]
                              [:= :abc_contracts.contract_id :abc_agent_commitments.commitment_contract])
             (merge-left-join [:commitment_outcomes :abc_commitment_outcomes]
                              [:= :abc_agent_commitments.commitment_id :abc_commitment_outcomes.outcome_id])
             (merge-left-join [:job_results :abc_job_results]
                              [:= :abc_jobs.job_id :abc_job_results.job_id])))
