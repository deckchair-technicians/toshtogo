(ns toshtogo.test.handler
  (:require [midje.sweet :refer :all]
            [ring.adapter.jetty :refer [run-jetty]]
            [toshtogo.web.handler :refer [app]]
            [toshtogo.client :refer :all]
            [toshtogo.api :refer [success error]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]))

(def client (app-sender-client app))

#_(fact "Work can be requesteed"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :request_body {:a-field "field value"}})

    (request-work! client [tag]) => (contains {:job_id (str job-id)
                                               :request_body {:a-field "field value"}})))

#_(fact "Work can only be requested once"
  (let [job-id (uuid)
        tag    (uuid-str)]

    (put-job! client job-id {:tags [tag]
                             :request_body {:a-field "field value"}})

    (request-work! client [tag])
    (request-work! client [tag]) => nil))

#_(fact "Agents can request work and then complete it"
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

#_(fact "Agents can report errors"
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

(facts "Job dependencies"
  (let [job-id (uuid)
        parent-tag    (uuid-str)
        child-tag     (uuid-str)]

    (put-job!
     client
     job-id {:tags [parent-tag]
             :request_body {:a "field value"}
             :dependencies
             [{:request_merge_path "/deps/[]"
               :tags [child-tag]
               :request_body {:b "child one"}}

              {:request_merge_path "/deps/[]"
               :tags [child-tag]
               :request_body {:b "child two"}}
              ]})

    (fact "No contract is created for parent job"
      (request-work! client [parent-tag]) => nil)

    (let [func (fn [job] (success (job :request_body)))]
      (fact "Dependencies are executed in order"
        (:contract @(do-work! client [child-tag] func))
        => (contains {:request_body {:b "child one"}}))

      (fact "Parent job is not ready until all dependencies complete"
        (request-work! client [parent-tag]) => nil)

      @(do-work! client [child-tag] func)

      (fact (str "Parent job is released when dependencies are complete, "
                 "with dependency responses merged into its request")
        (let [contract (request-work! client [parent-tag])]
          contract
          => (contains {:request_body {:a "field value"}})

          (contract :dependencies)
          => (contains [(contains {:result_body {:b "child one"}})
                        (contains {:result_body {:b "child two"}})]
                       :in-any-order))))))
