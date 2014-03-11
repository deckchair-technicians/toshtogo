(ns toshtogo.test.util.job-requests-test
  (:require [midje.sweet :refer :all]
            [toshtogo.util.core :refer [uuid]]
            [toshtogo.server.util.job-requests :refer :all]))

(fact "flattened-dependencies flattens dependency hierarchy"
      (flattened-dependencies {:job_id "1"
                               :dependencies [{:job_id "1.1"
                                               :dependencies [{:job_id "1.1.1"}]}
                                              {:job_id "1.2"}]})
      => (contains [{:job_id "1.1" :parent_job_id "1"}
                    {:job_id "1.1.1" :parent_job_id "1.1"}
                    {:job_id "1.2" :parent_job_id "1"}]
                   :in-any-order))

(fact "flattened-dependencies assigns ids to dependencies"
      (flattened-dependencies {:job_id "1"
                               :dependencies [{:job_id nil}]})
      => (contains [{:job_id ..generated-job-id.. :parent_job_id "1"}])
      (provided (uuid) => ..generated-job-id..))