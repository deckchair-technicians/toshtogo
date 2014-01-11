(ns toshtogo.test.handler
  (:require [midje.sweet :refer :all]
            [ring.adapter.jetty :refer [run-jetty]]
            [toshtogo.handler :refer [app]]
            [toshtogo.client :refer :all]
            [toshtogo.util :refer [uuid uuid-str debug]]))

(def client (app-sender-client app))

(fact "Work can be requesteed"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :body {:a-field "field value"}})

    (request-work! client [tag]) => (contains {:job_id (str job-id) :body {:a-field "field value"}})))

(fact "Work can only be requested once"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :body {:a-field "field value"}})

    (request-work! client [tag])
    (request-work! client [tag]) => nil
    ))

(fact "Agents can request work and then complete it"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :body {:a-field "field value"}})

    (let [f                         (fn [job] (success {:response-field "all good"}))
          {:keys [contract result]} @(do-work! client [tag] f)]
      contract
      => (contains {:job_id (str job-id) :body {:a-field "field value"}})
      result
      => (contains {:outcome :success :result {:response-field "all good"}}))))
