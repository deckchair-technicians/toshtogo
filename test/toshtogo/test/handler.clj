(ns toshtogo.test.handler
  (:require [midje.sweet :refer :all]
            [ring.adapter.jetty :refer [run-jetty]]
            [toshtogo.handler :refer [app]]
            [toshtogo.client :refer :all]
            [toshtogo.contracts :refer [success error]]
            [toshtogo.util :refer [uuid uuid-str debug]]))

(def client (app-sender-client app))

(fact "Work can be requesteed"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :request_body {:a-field "field value"}})

    (request-work! client [tag]) => (contains {:job_id (str job-id)
                                               :request_body {:a-field "field value"}})))

(fact "Work can only be requested once"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :request_body {:a-field "field value"}})

    (request-work! client [tag])
    (request-work! client [tag]) => nil))

(fact "Agents can request work and then complete it"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :request_body {:a-field "field value"}})

    (let [func                      (fn [job] (success {:response-field "all good"}))
          {:keys [contract result]} @(do-work! client [tag] func)]
      contract
      => (contains {:job_id (str job-id) :request_body {:a-field "field value"}})
      result
      => (contains {:outcome :success :result {:response-field "all good"}}))

    (get-job client job-id)
    => (contains {:outcome "success" :result_body {:response-field "all good"}})))

(fact "Agents can report errors"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :request_body {:a-field "field value"}})

    (let [func                      (fn [job] (error "something went wrong"))
          {:keys [contract result]} @(do-work! client [tag] func)]
      contract
      => (contains {:job_id (str job-id) :request_body {:a-field "field value"}})
      result
      => (contains {:outcome :error :error "something went wrong"}))

    (get-job client job-id)
    => (contains {:outcome "error" :error "something went wrong"})))
