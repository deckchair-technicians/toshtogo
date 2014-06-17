(ns toshtogo.test.functional.logging-test
  (:require [midje.sweet :refer :all]
            [toshtogo.server.core :refer [dev-app]]
            [toshtogo.server.logging :refer :all]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.midje-schema :refer :all]
            [toshtogo.test.functional.test-support :refer :all])
  (:import [toshtogo.client BadRequestException]
           [toshtogo.server.logging DeferredLogger]))

(background (before :contents @migrated-dev-db))

(defn logging-client
  "Creates an app (not HTTP) client. App is configured with a logger that
  logs to a list of log events inside log-attom.

  Returns [log-atom log-client]"
  []
  (let [logs-atom (atom [])
        logger (DeferredLogger. logs-atom)
        logging-app (dev-app :debug false :logger-factory (constantly logger))
        log-client (test-client :should-retry false
                                :client-config {:type :app :app logging-app})]

    [logs-atom log-client]))

(defn consume-logs
  "Returns contents of log-atom and resets it to an empty list"
  [logs-atom]
  (let [logs @logs-atom]
    (reset! logs-atom [])
    (or logs [])))

(let [[logs-atom log-client] (logging-client)
      job-id (uuid)
      job-type (keyword (uuid-str))]
  (put-job! log-client job-id (job-req {} job-type))

  (fact "New job is logged"
        (consume-logs logs-atom)
        => (matches (contains-items [{:event_type :new_job
                                      :event_data {:job_id job-id
                                                   :job_type job-type}}])))

  (fact "Putting job with different content throws an idempotency exception"
        (put-job! log-client job-id (job-req {:not "the same"} job-type))
        => (throws BadRequestException))

  (fact "Server error is logged"
        (consume-logs logs-atom)
        => (matches (contains-items [{:event_type :server_error
                                      :event_data {:stacktrace (contains-string "Previous put for create-job")}}])))

  (fact "Job completes successfully"
    @(do-work! log-client {:job_id job-id} return-success)
    => truthy)

  (fact "Commitment start and end is logged"
        (consume-logs logs-atom)
        => (matches (contains-items [{:event_type :commitment_started
                                      :event_data {:job_id job-id}}

                                     {:event_type :commitment_result
                                      :event_data {:job_id job-id
                                                   :result {:outcome :success}}}]))))
