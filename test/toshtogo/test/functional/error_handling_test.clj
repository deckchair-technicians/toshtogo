(ns toshtogo.test.functional.error-handling-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]
            [toshtogo.client
             [agent :refer [job-consumer]]
             [core :as ttc]
             [protocol :refer :all]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [schema.core :as sch]
            [toshtogo.test.midje-schema :refer :all]
            [toshtogo.test.functional.test-support :refer :all])

  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent ExecutionException)))

(background (before :contents @migrated-dev-db))

(defn lift [e]
  (if (instance? ExecutionException e)
    (lift (.getCause e))
    (throw e)))

(defmacro lift-exceptions
  [& body]
  `(try
     ~@body
     (catch Exception e#
       (throw (lift e#))))             )

(defn handle [request]
  (assoc request :something "abc"))

(defn handler [dependency]
  (-> handle
      ; some wrapping
      ))

; NB: don't use a client that logs to the console on error, or this will be a mess.
(let [client-no-logging (test-client :error-fn (constantly nil) :debug false)]
  (fact "accidentally passing job-consumer the handler builder instead of the handler results in an error response being
  sent to toshtogo, rather than a client-side error (test case from real world use)"
    (let [job-type (uuid-str)
          job-id (uuid)
          consumer (job-consumer (constantly client-no-logging)
                                 job-type
                                 handler)]

      (put-job! client-no-logging job-id (job-req {:a-field "field value"} job-type))

      (consumer nil)

      (:error (get-job client-no-logging job-id))
      => (matches {:message             #"Problem sending result. Result cannot be json encoded."
                   :original_result_str String})))

  (fact "Sending an invalid job results in client exception (i.e. does not get stuck in retry loop)"
    (lift-exceptions (put-job! client-no-logging (uuid) {:not "a job"}))
    => (throws ExceptionInfo "Bad Request"))

  (fact "Sending an invalid response results in client exception (i.e. does not get stuck in retry loop)"
    (let [job-id (uuid)
          job-type (uuid-str)
          _ (put-job! client-no-logging job-id (job-req {:a-field "field value"} job-type))
          contract (request-work! client-no-logging job-type)]

      contract => truthy

      (lift-exceptions (complete-work! client-no-logging (:commitment_id contract) {:not "a valid result"}))
      => (throws ExceptionInfo "Bad Request")))


  (fact "do-work! on client reports unhandled exceptions, including ex-data in the error response"
    (let [job-id (uuid)
          job-type (uuid-str)]

      (put-job! client-no-logging job-id (job-req {:a-field "field value"} job-type))

      (let [func (fn [job] (throw (ex-info "Error from handler" {:ex "data"})))
            {:keys [contract result]} @(do-work! client-no-logging job-type func)]

        contract
        => (matches {:job_id job-id :request_body {:a-field "field value"}})
        result
        => (matches {:outcome :error
                     :error   {:message    #"Error from handler"
                               :stacktrace #"Error from handler"
                               :ex_data    {:ex "data"}}}))

      (get-job client-no-logging job-id)
      => (matches {:outcome :error
                   :error   {:message    #"Error from handler"
                             :stacktrace #"Error from handler"
                             :ex_data    {:ex "data"}}})))

  (fact "do-work! reports as much information about unhandled exceptions as it can, even if the result itself is the problem"
    (let [job-type (uuid-str)]

      (fact "unserialisable result"
        (let [job-id (uuid)
              func (fn [job] (success {:cannot-be-json-encoded (Object.)}))]

          (put-job! client-no-logging job-id (job-req {:a-field "field value"} job-type))

          @(do-work! client-no-logging job-type func)

          (:error (get-job client-no-logging job-id))
          => (matches {:message             #"Cannot JSON encode object"
                       :original_result_str #"cannot-be-json-encoded"})))))

  (fact "Idempotency exceptions are marked as client errors"
    (let [job-id (uuid)]

      (put-job! client-no-logging job-id (job-req {:a-field "field value"} (uuid-str)))

      (lift-exceptions (put-job! client-no-logging job-id
                                 (job-req {:a-field "DIFFERENT value"} (uuid-str))))
      => (throws ExceptionInfo "Bad Request"))))
