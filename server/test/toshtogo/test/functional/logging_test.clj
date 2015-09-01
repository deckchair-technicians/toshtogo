(ns toshtogo.test.functional.logging-test
  (:require [midje.sweet :refer :all]
            [toshtogo.server.core :refer [dev-app]]
            [toshtogo.server.logging :refer :all]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [vice
             [midje :refer [matches]]
             [schemas :refer [in-any-order in-order]]]
            [toshtogo.test.functional.test-support :refer :all])
  (:import [clojure.lang ExceptionInfo]
           [toshtogo.server.logging DeferredLogger]))

(background (before :contents @migrated-dev-db))

(defn contains-items [schemas]
  (in-any-order schemas :extras-ok true))

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

(let [[logs-atom log-client] (logging-client)]
  (let [job-id (uuid)
        job-type (keyword (uuid-str))]
    (put-job! log-client job-id (job-req {} job-type)) => truthy

    (fact "New job is logged"
          (consume-logs logs-atom)
          => (matches (contains-items [{:event_type :new_job
                                        :event_data {:job_id   job-id
                                                     :job_type job-type}}])))

    (fact "Putting job with different content throws an idempotency exception"
          (put-job! log-client job-id (job-req {:not "the same"} job-type))
          => (throws ExceptionInfo "Bad Request"))

    (fact "Server error is logged"
          (consume-logs logs-atom)
          => (matches (contains-items [{:event_type :server_error
                                        :event_data {:stacktrace #"Previous put for create-job"}}])))

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

  (let [job-id (uuid)]
    (put-job! log-client job-id (job-req {} (uuid-str))) => truthy
    @(do-work! log-client {:job_id job-id} (constantly (error "something went wrong")))
    => truthy

    (fact "Job errors are logged"
          (consume-logs logs-atom)
          => (matches (contains-items [{:event_type :commitment_result
                                        :event_data {:job_id job-id
                                                     :result {:outcome :error
                                                              :error   {:stacktrace "something went wrong"}}}}]))))

  (let [job-id (uuid)]
    (put-job! log-client job-id (job-req {} (uuid-str))) => truthy
    (consume-logs logs-atom)

    @(do-work! log-client {:job_id job-id} (fn [_] {:not_valid_response "response"}))
    => truthy

    (fact "If client sends badly formed result, we get a server error and a commitment result"
      (remove #(= :request (:event_type %)) (consume-logs logs-atom))
      => (matches (in-order [{:event_type :commitment_started}

                             {:event_type :server_error
                              :event_data {:stacktrace #"not_valid_response"}}

                             {:event_type :commitment_result
                              :event_data {:job_id job-id
                                           :result {:outcome :error
                                                    :error   {:stacktrace #"Bad Request"}}}}])))))
