(ns toshtogo.test.jobs
  (:require [midje.sweet :refer :all]
            [toshtogo.agents :refer [get-agent-details]]
            [toshtogo.jobs :refer :all]))

(fact "Creates a job"
  (let [agent-details (get-agent-details "test" "test")]
    (job ...id... agent-details {:data "value"} [:tag-one :tag-two])
    => {:job_id ...id...
        :agent agent-details
        :tags [:tag-one :tag-two]
        :body {:data "value"}}))
