(ns toshtogo.test.server.logging-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.midje-schema :refer :all]
            [toshtogo.client.protocol :refer [job-req success]]
            [toshtogo.server.api :refer [to-job-record]]
            [toshtogo.server.preprocessing :refer [normalise-job-tree]]
            [toshtogo.util.core :refer [uuid]]
            [toshtogo.test.functional.test-support :refer [agent-details]]

            [toshtogo.server.logging :refer :all]))

(fact "Event constructors produce valid events"
      (new-job-event (-> (job-req {:some :request} :a_job_type)
                         (assoc :home_tree_id (uuid))
                         (normalise-job-tree (uuid))
                         (to-job-record)))
      => (matches LoggingEvent)

      (commitment-started-event (uuid)
                                {:job_id       (uuid)
                                 :job_type     :a_job_type
                                 :job_name     "Some name"
                                 :request_body {:some :request}}
                                agent-details)
      => (matches LoggingEvent)

      (commitment-result-event {:job_id           (uuid)
                                :commitment_id (uuid)
                                :job_type         :a_job_type
                                :job_name     "Some name"
                                :request_body     {:some :request}}
                               agent-details
                               (success {:some :result}))
      => (matches LoggingEvent))