(ns toshtogo.test.functional.error-handling-test
  (:import (toshtogo.client BadRequestException)
           (java.util.concurrent ExecutionException))
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]))

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

(fact "Sending an invalid job results in client exception (i.e. does not get stuck in retry loop)"
      (lift-exceptions (put-job! client (uuid) {:not "a job"}))
      => (throws BadRequestException))

(fact "Sending an invalid response results in client exception (i.e. does not get stuck in retry loop)"
      (let [job-id (uuid)
            job-type (uuid-str)
            _ (put-job! client job-id (job-req {:a-field "field value"} job-type))
            contract (request-work! client job-type)]

        contract => truthy

        (lift-exceptions (complete-work! client (:commitment_id contract) {:not "a valid result"}))
        => (throws BadRequestException)))

(fact "Idempotency exceptions are marked as client errors"
      (let [job-id (uuid)]

        (put-job! client job-id (job-req {:a-field "field value"} (uuid-str)))

        (lift-exceptions (put-job! client job-id (job-req {:a-field "DIFFERENT value"} (uuid-str))))
        => (throws BadRequestException)))

(future-fact "Exceptions as a result of bad requests are thrown immediately")