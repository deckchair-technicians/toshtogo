(ns toshtogo.test.functional.concurrency-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.midje-schema :refer :all]
            [schema.core :as sch]
            [clojure.stacktrace :refer [print-cause-trace]]

            [toshtogo.client.agent :refer :all]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]))

(defn report-to-atom [a]
  (fn [e] (swap! a #(cons e %))))

(facts "If lots of agents are requesting work at the same time, no errors escape from the server due to clashes"
      (let [job-type (uuid-str)
            num-threads 10
            barrier (promise)
            thread-results (atom [])
            test-complete (promise)]

        (doseq [i (range num-threads)]
          (put-job! client (uuid) (job-req {:job_num i} job-type))
          (future @barrier
                  (try
                    (let [the-client (test-client :should-retry false :timeout nil)
                          job-returned (:request_body (request-work! the-client job-type))]
                      (swap! thread-results #(cons job-returned %))
                      (when (= num-threads (count @thread-results))
                        (deliver test-complete true)))
                    (catch Throwable e
                      (swap! thread-results #(cons (.getMessage e) %))))))


        (deliver barrier nil)

        (fact "all requests completed"
              (deref test-complete 20000 false) => truthy)

        (fact "no requests failed"
              @thread-results => (matches [{sch/Any sch/Any}]))))