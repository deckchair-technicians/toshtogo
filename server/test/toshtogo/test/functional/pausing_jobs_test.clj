(ns toshtogo.test.functional.pausing-jobs-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]))

(background (before :contents @migrated-dev-db))

(facts "Agents receive a cancellation signal in the heartbeat response when jobs are paused"
       (let [job-id (uuid)
             job-type (uuid-str)
             commitment-id (promise)]

         (put-job! client job-id (job-req {} job-type))

         (let [commitment (future (do-work! client job-type (fn [job]
                                                              (deliver commitment-id (job :commitment_id))
                                                              (Thread/sleep 10000)
                                                              (error {:message "Should never return"}))))]
           (future-done? commitment) => falsey

           (heartbeat! client @commitment-id)
           => (contains {:instruction :continue})

           (get-job client job-id)
           => (contains {:outcome :running})

           (pause-job! client job-id)

           (get-job client job-id)
           => (contains {:outcome :cancelled})

           (deref commitment 5000 nil)
           => (contains {:result {:outcome :cancelled}})

           (Thread/sleep 100)
           (future-done? commitment) => truthy

           (future-cancel commitment))))

(fact "Paused jobs can be retried"
      (let [job-id (uuid)
            job-type (uuid-str)]

        (put-job! client job-id (job-req {} job-type))

        (pause-job! client job-id)

        (get-job client job-id)
        => (contains {:outcome :cancelled})

        (retry-job! client job-id)

        (get-job client job-id)
        => (contains {:outcome :waiting})

        (do-work! client job-type return-success)
        => truthy

        (get-job client job-id)
        => (contains {:outcome :success})))

(fact "Retry nested job restarts all"
  (let [job-id (uuid)
        child-one-id (uuid)
        parent-job-type (uuid-str)
        child-job-type (uuid-str)]

    (put-job!
      client
      job-id (job-req
               {:a "field value"} parent-job-type
               :dependencies [(-> (job-req {:b "child one"} child-job-type)
                                  (with-job-id child-one-id))]))

    (pause-job! client job-id)

    (get-job client job-id)
    => (contains {:outcome :cancelled})

    (get-job client child-one-id)
    => (contains {:outcome :cancelled})

    (retry-job! client job-id)

    (get-job client job-id)
    => (contains {:outcome :waiting})

    (get-job client child-one-id)
    => (contains {:outcome :waiting})

    (do-work! client child-job-type
               (constantly (error {:message "something went wrong"})))

    (pause-job! client job-id)

    (get-job client job-id)
    => (contains {:outcome :cancelled})

    (get-job client child-one-id)
    => (contains {:outcome :error})

    (retry-job! client job-id)

    (get-job client job-id)
    => (contains {:outcome :waiting})

    (get-job client child-one-id)
    => (contains {:outcome :waiting})))

