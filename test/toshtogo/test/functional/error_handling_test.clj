(ns toshtogo.test.functional.error-handling-test
  (:import (toshtogo.client BadRequestException)
           (java.util.concurrent ExecutionException))
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]
            [toshtogo.client.core :as ttc]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [schema.core :as sch]
            [toshtogo.test.midje-schema :refer :all]
            [toshtogo.test.functional.test-support :refer :all]))

(background (before :contents @migrated-dev-db))

(def non-error-logging-client (ttc/client client-config
                        :error-fn (fn [e] nil)
                        :debug false
                        :timeout 1000
                        :system "client-test"
                        :version "0.0"))

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

(fact "Sending an invalid job results in client exception (i.e. does not get stuck in retry loop)"
      (lift-exceptions (put-job! non-error-logging-client (uuid) {:not "a job"}))
      => (throws BadRequestException))

(fact "Sending an invalid response results in client exception (i.e. does not get stuck in retry loop)"
      (let [job-id (uuid)
            job-type (uuid-str)
            _ (put-job! non-error-logging-client job-id (job-req {:a-field "field value"} job-type))
            contract (request-work! non-error-logging-client job-type)]

        contract => truthy

        (lift-exceptions (complete-work! non-error-logging-client (:commitment_id contract) {:not "a valid result"}))
        => (throws BadRequestException)))

(fact "do-work! will try to send exceptions resulting from a bad response back to the server"
      (let [job-id (uuid)
            job-type (uuid-str)
            _ (put-job! non-error-logging-client job-id (job-req {} job-type))
            result @(do-work! non-error-logging-client job-type (constantly {:not-a "valid response"}))]

        result => truthy

        (get-job non-error-logging-client job-id)
        => (matches {:error (sch/both #"not-a" #"valid response")})))


(fact "Idempotency exceptions are marked as client errors"
      (let [job-id (uuid)]

        (put-job! non-error-logging-client job-id (job-req {:a-field "field value"} (uuid-str)))

        (lift-exceptions (put-job! non-error-logging-client job-id (job-req {:a-field "DIFFERENT value"} (uuid-str))))
        => (throws BadRequestException)))

(future-fact "Exceptions as a result of bad requests are thrown immediately")