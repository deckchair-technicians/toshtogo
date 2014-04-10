(ns toshtogo.test.util.job-requests-test
  (:require [midje.sweet :refer :all]
            [toshtogo.util.core :refer [uuid]]
            [toshtogo.server.util.job-requests :refer :all]))

(fact "normalised-job-list flattens dependency hierarchy"
      (normalised-job-list {:job_id "1"
                               :dependencies [{:job_id "1.1"
                                               :dependencies [{:job_id "1.1.1"}]}
                                              {:job_id "1.2"}]})
      => (contains [(contains {:job_id "1" :parent_job_id nil})
                    (contains {:job_id "1.1" :parent_job_id "1"})
                    (contains {:job_id "1.1.1" :parent_job_id "1.1"})
                    (contains {:job_id "1.2" :parent_job_id "1"})]))

(fact "normalised-job-list assigns ids to dependencies"
      (normalised-job-list {:job_id "1"
                               :dependencies [{:job_id nil}]})
      => (contains [(contains {:job_id ..generated-job-id..
                               :fungibility_group_id ..generated-job-id..
                               :parent_job_id "1"})])
      (provided (uuid) => ..generated-job-id..))

(fact "normalised-job-list preserves job_type"
      (normalised-job-list {:job_id "1"
                            :job_type "job type 1"
                               :dependencies [{:job_id nil
                                               :job_type "job type 2"
                                               }]})
      => (contains [(contains {:job_id "1"
                               :job_type "job type 1"}
                              {:job_id ..generated-job-id..
                               :job_type "job type 2"})])
      (provided (uuid) => ..generated-job-id..))

(fact "normalised-job-list preserves parent_job_id"
      (normalised-job-list {:job_id "1"
                            :job_type "job type 1"
                               :dependencies [{:job_id nil
                                               :job_type "job type 2"
                                               }]})
      => (contains [(contains {:job_id "1"
                               :job_type "job type 1"}
                              {:job_id ..generated-job-id..
                               :job_type "job type 2"})])
      (provided (uuid) => ..generated-job-id..))

(fact "normalised-job-list sets fungibility group ids"
      (normalised-job-list {:job_id "1"
                            :dependencies [{:job_id "1.1"
                                            :fungibility_group_id "specifically set in 1.1"
                                            :dependencies [{:job_id "1.1.1" :fungible_under_parent true}]}
                                           {:job_id "1.2"}]})
      => (contains [(contains {:job_id "1" :fungibility_group_id "1"}) ; set from job_id
                    (contains {:job_id "1.1" :fungibility_group_id "specifically set in 1.1"}) ; specifically set
                    (contains {:job_id "1.1.1" :fungibility_group_id "specifically set in 1.1"}) ; :fungible_under_parent
                    (contains {:job_id "1.2" :fungibility_group_id "1.2"})] ; :has parent, but no :fungibility_group_id set
                    ))