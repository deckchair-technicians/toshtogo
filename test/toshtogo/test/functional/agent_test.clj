(ns toshtogo.test.functional.agent-test
  (:require [midje.sweet :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.client.agent :refer :all]
            [toshtogo.client.protocol :refer :all]))

(background (before :contents @migrated-dev-db))

(fact "Stopping the service does indeed stop listening for jobs"
      (let [job-id (uuid)
            job-type (uuid-str)
            service (start-service (job-consumer
                                     (constantly client)
                                     job-type
                                     return-success
                                     :sleep-on-no-work-ms 0))]
        (deref (stop service) 500 "didn't stop")
        => #(not= "didn't stop")

        (put-job! client job-id (job-req {:some "request"} job-type))

        (Thread/sleep 100)

        (get-job client job-id)
        => (contains {:outcome :waiting})))

