(ns toshtogo.test.functional.concurrency-test
  (:require [midje.sweet :refer :all]
            [vice.midje :refer [matches]]
            [schema.core :as sch]
            [clojure.stacktrace :refer [print-cause-trace]]

            [toshtogo.client.agent :refer :all]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]))

(background (before :contents @migrated-dev-db))

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
                      (swap! thread-results #(cons job-returned %)))
                    (catch Throwable e
                      (swap! thread-results #(cons (.getMessage e) %))))

                  (when (= num-threads (count @thread-results))
                    (deliver test-complete true))))


        (deliver barrier nil)

        (fact "all request-work! calls completed (but may have thrown exceptions)"
              (deref test-complete 10000 false) => truthy)

        (fact "no requests threw exceptions"
              @thread-results => (matches [{sch/Any sch/Any}]))

        (fact "all jobs were requested"
              (:data (get-jobs client {:job_type job-type}))
              => (matches [{:outcome :running}]))))


(facts "If lots of agents are requesting FUNGIBLE work at the same time, no errors escape from the server due to clashes"
       (let [job-type (uuid-str)

             fungible-job-type (uuid-str)
             fungibility-key (uuid)

             num-threads 10
             barrier (promise)

             threads-waiting (atom 0)
             all-threads-ready (promise)

             thread-results (atom [])
             test-complete (promise)

             dep (-> (job-req {} fungible-job-type)
                     (with-fungibility-key fungibility-key))
             add-fungible-dependency (add-dependencies dep)]

         (doseq [i (range num-threads)]
           (future (try
                     (let [job-id       (uuid)
                           the-client   (test-client :should-retry false :timeout nil)]

                       (put-job! the-client job-id (job-req {:job_num i} job-type))

                       (let [job-returned (request-work! the-client {:job_id job-id})]

                         (assert job-returned "no job returned")

                         (swap! threads-waiting inc)
                         (when (= num-threads @threads-waiting)
                           (deliver all-threads-ready true))

                         @barrier

                         (swap! thread-results #(cons (complete-work! the-client
                                                                      (:commitment_id job-returned)
                                                                      add-fungible-dependency)
                                                      %))))
                     (catch Throwable e
                       (swap! thread-results #(cons (.getMessage e) %))))

                   (when (= num-threads (count @thread-results))
                     (deliver test-complete true))))


         (fact "all threads get to barrier"
               (deref all-threads-ready 10000 false) => truthy)

         (deliver barrier nil)

         (fact "all calls completed (but may have thrown exceptions)"
               (deref test-complete 10000 false) => truthy)

         (fact "no requests threw exceptions"
               @thread-results => (matches [{sch/Any sch/Any}]))

         (fact "Only one fungible job was created"
               (:data (get-jobs client {:job_type fungible-job-type}))
               => #(= 1 (count % )))))
