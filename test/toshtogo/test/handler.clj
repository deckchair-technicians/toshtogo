(ns toshtogo.test.handler
  (:require [midje.sweet :refer :all]
            [ring.adapter.jetty :refer [run-jetty]]
            [toshtogo.handler :refer [app]]
            [toshtogo.client :refer :all]
            [toshtogo.util :refer [uuid uuid-str]]))

(def client (app-sender-client app))

  (fact "Adding a job with no dependencies triggers creation of a contract"
    (let [job-id (uuid)
          tag    (uuid-str) ; dirty database
          ]
      (println "TAAAG" tag)
      (put-job! client job-id {:tags [tag]
                               :body {:a-field "field value"}})
      => (contains {:body {:a-field "field value"}})

      (request-work! client [tag])
      => (contains {:job_id (str job-id) :body {:a-field "field value"}})))
