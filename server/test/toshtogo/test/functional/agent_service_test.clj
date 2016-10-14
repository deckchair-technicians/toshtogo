(ns toshtogo.test.functional.agent-service-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.framework
             [test-ids :refer [id!]]
             [runner :refer :all]
             [steps :refer :all]
             [test-handlers :refer [return-success wait-for-shutdown-promise]]]

            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.client.agent :refer :all]
            [toshtogo.client.protocol :refer :all]))

(background (before :contents @migrated-dev-db))

(with-redefs [toshtogo.client.protocol/heartbeat-time 10]
  (fact "Started service listens for jobs"
    (scenario
      (given (agent-is-running (id! :job-type)
                               return-success))
      (when-we (put-a-job (id! :job-id)
                          (job-req {:some "request"} (id! :job-type))))
      (and-we (wait-for-job-state :success (id! :job-id)))
      (then-expect (job-state (id! :job-id))
                   (contains {:outcome :this-should-fail}))))

  (fact "Can make more complicated queries for work"
    (scenario
      (given (agent-is-running
               {:job_type [(id! :type-one) (id! :type-two)]}
               return-success))
      (when-we (put-a-job (id! :job-one)
                          (job-req {:some "request"} (id! :type-one))))
      (and-we (wait-for-job-state :success (id! :job-one)))

      (then-expect (job-state (id! :job-one))
                   (contains {:outcome :success}))

      (when-we (put-a-job (id! :job-two)
                          (job-req {:some "request"} (id! :type-two))))

      (and-we (wait-for-job-state :success (id! :job-two)))

      (then-expect (job-state (id! :job-two))
                   (contains {:outcome :success}))))

  (fact "Stopping the service does indeed stop listening for jobs"
    (scenario
      (given (agent-is-running
               {:job_type (id! :type-one)}
               return-success))
      (when-we (stop-service))
      (and-we (put-a-job (id! :job-one)
                         (job-req {} (id! :type-one))))
      (and-we (wait-millis 100))
      (then-expect (job-state (id! :job-one))
                   (contains {:outcome :waiting}))))

  (fact "job-consumer passes through shutdown-promise"
    (scenario
      (given (agent-is-running
               (id! :type-one)
               wait-for-shutdown-promise))

      (when-we (put-a-job (id! :job-one)
                          (job-req {} (id! :type-one))))

      (and-we (wait-for-job-state :running (id! :job-one)))

      (then-expect (job-state (id! :job-one)) #(= % :running))

      (when-we (stop-service))

      (and-we (wait-millis 100))

      (then-expect
        (job-state (id! :job-one))
        (contains {:outcome :error
                   :error   {:message "shutdown-promise triggered"}}))))

  (fact "Stopping the service twice does not throw an exception"
    (scenario
      (given (agent-is-running
               (uuid-str)
               return-success))

      (stop-service)
      (stop-service)))

  (fact "Started service handles exceptions"
    (let [got-past-exceptions (promise)
          count (atom 0)
          service (start-service (fn [_] (if (< @count 2)
                                           (do
                                             (swap! count inc)
                                             (throw (AssertionError. "Intermittent exception")))
                                           (deliver got-past-exceptions "got here")))
                                 :error-handler (constantly nil))]
      (deref got-past-exceptions 200 "service stopped at exception")
      => #(not= "service stopped at exception" %)

      (stop service)))
  )
