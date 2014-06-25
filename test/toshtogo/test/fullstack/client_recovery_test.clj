(ns toshtogo.test.fullstack.client-recovery-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.test-support :refer [test-client localhost in-process return-success]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid-str uuid]]
            [toshtogo.server.core :refer [start!]]
            [toshtogo.client.agent :refer [start-service job-consumer]])
  (:import [java.net ServerSocket]
           [toshtogo.client RecoverableException]))

(defn available-port []
  (let [socket (ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(facts "Server outage recovery"
      (let [job-id (uuid)
            job-type (uuid-str)
            app-client (test-client :client-config in-process :timeout nil :should-retry false)
            commitment (atom nil)]

        (fact "Job created ok"
              (put-job! app-client job-id (job-req {} job-type))
              => truthy)

        (fact "Requested work ok"
              (reset! commitment (request-work! app-client {:job_id job-id}))
              => truthy)

        (let [port         (available-port)
              http-client-error (promise)
              http-client  (test-client :client-config {:type :http :base-url (str "http://localhost:" port)}
                                        :error-fn (fn [e]
                                                    (deliver http-client-error e))
                                        :timeout nil)
              complete-work-result       (future (complete-work! http-client (:commitment_id @commitment) (success {})))
              ; Wait for client to encounter an exception
              _            (deref http-client-error 1000 nil)
              stop-server  (start! false port false)]

          (fact "HTTP client encountered an error before server came up, as expected"
                (realized? http-client-error) => truthy
                (when (realized? http-client-error)
                  @http-client-error
                  => #(instance? RecoverableException %)))

          (fact "complete-work! eventually returned affirmatively"
            (deref complete-work-result 2000 "DID NOT RESPOND")
            => truthy)

          (fact "Job is completed successfully"
                (get-job app-client job-id)
                => (contains {:outcome :success}))

          (stop-server))))
