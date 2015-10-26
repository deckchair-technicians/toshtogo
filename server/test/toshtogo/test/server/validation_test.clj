(ns toshtogo.test.server.validation-test
  (:require [midje.sweet :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.server.validation :refer :all])
  (:import (clojure.lang ExceptionInfo)))

(fact "Job validation passes for valid job"
      (let [valid-job (-> (job-req {:some "data"} :some-job-type)
                          (with-job-id (uuid))
                          (with-name "some name")
                          (with-notes "some notes")
                          (with-fungibility-key (uuid-str))
                          (with-dependency-on (uuid) (uuid))
                          (with-dependencies [(job-req {:child :request} :child-job-type)]))]
        (validated valid-job JobRequest)
        => valid-job))

(fact "Job validation fails for invalid job"
      (let [invalid-job (job-req "not a map" :some-job-type)]
        (validated invalid-job JobRequest)
        => (throws ExceptionInfo)))
