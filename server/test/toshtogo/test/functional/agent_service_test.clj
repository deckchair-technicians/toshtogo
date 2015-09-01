(ns toshtogo.test.functional.agent-service-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :as t]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.client.agent :refer :all]
            [toshtogo.client.protocol :refer :all]))

(background (before :contents @migrated-dev-db))

(with-redefs [toshtogo.client.protocol/heartbeat-time 10]

  (fact "Started service listens for jobs"
    (let [job-id (uuid)
          job-type (uuid-str)
          service (start-service (job-consumer
                                  (constantly client)
                                  job-type
                                  return-success
                                  :sleep-on-no-work-ms 1))]

      (put-job! client job-id (job-req {:some "request"} job-type))

      (deref (future (while (-> (get-job client job-id)
                                :outcome
                                (not= :success))
                       (Thread/sleep 100)))
             2000 nil)

      (get-job client job-id)
      => (contains {:outcome :success})

      (stop service)
      ))

  (fact "Can make more complicated queries for work"
    (let [job-id-1   (uuid)
          job-id-2   (uuid)
          job-type-1 (uuid-str)
          job-type-2 (uuid-str)
          service    (start-service (job-consumer
                                     (constantly client)
                                     {:job_type [job-type-1 job-type-2]}
                                     return-success
                                     :sleep-on-no-work-ms 1))]

      (put-job! client job-id-1 (job-req {:some "request"} job-type-2))

      (deref (future (while (-> (get-job client job-id-1)
                                :outcome
                                (not= :success))
                       (Thread/sleep 100)))
             2000 nil)

      (get-job client job-id-1)
      => (contains {:outcome :success})

      (put-job! client job-id-2 (job-req {:some "request"} job-type-1))

      (deref (future (while (-> (get-job client job-id-2)
                                :outcome
                                (not= :success))
                       (Thread/sleep 100)))
             2000 nil)

      (get-job client job-id-2)
      => (contains {:outcome :success})

      (stop service)
      ))

  (fact "Stopping the service does indeed stop listening for jobs"
    (let [job-id (uuid)
          job-type (uuid-str)
          service (start-service (job-consumer
                                  (constantly client)
                                  job-type
                                  return-success
                                  :sleep-on-no-work-ms 1))]

      (deref (future (stop service)) 100 "didn't stop")
      => #(not= "didn't stop" %)

      (put-job! client job-id (job-req {:some "request"} job-type))

      (Thread/sleep 100)

      (get-job client job-id)
      => (contains {:outcome :waiting})
      ))

  (fact "job-consumer passes through shutdown-promise"
    (let [job-id (uuid)
          job-type (uuid-str)
          wait-for-shutdown-promise (fn [job]
                                      (while (not (realized? (:shutdown-promise job)))
                                        ; keep spinning
                                        )
                                      (error {:message "shutdown-promise triggered"}))
          service (start-service (job-consumer
                                  (constantly client)
                                  job-type
                                  wait-for-shutdown-promise
                                  :sleep-on-no-work-ms 1))]

      (put-job! client job-id (job-req {:some "request"} job-type))

      (deref (future (while (-> (get-job client job-id)
                                :outcome
                                (= :waiting))
                       (Thread/sleep 50)))
             2000 "job wasn't picked up")
      => #(not= "job wasn't picked up" %)

      (stop service)

      (get-job client job-id)
      => (contains {:outcome :error
                    :error   {:message "shutdown-promise triggered"}})))

  (fact "Stopping the service twice does not throw an exception"
    (let [service (start-service (job-consumer
                                  (constantly client)
                                  (uuid-str)
                                  return-success
                                  :sleep-on-no-work-ms 1))]

      (stop service)
      (stop service)))

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
