(ns toshtogo.test.client.middleware-test
  (:require [midje.sweet :refer :all]
            [org.httpkit.server :refer [run-server]]
            [flatland.useful.map :refer [update map-keys]]
            [toshtogo.util.json :as json]
            [toshtogo.test.midje-schema :refer :all]
            [toshtogo.client.middleware :refer :all]
            [toshtogo.client.protocol :refer :all]))

(fact "Merging child jobs"
  ((wrap-merge-dependency-results identity) {:request_body {:parent_field 1}
                                             :dependencies [{:job_type    :child_job_1
                                                             :result_body {:child_field_1 2}}

                                                            {:job_type    :child_job_2
                                                             :result_body {:child_field_2 3}}]})
  => (contains {:request_body {:parent_field 1
                               :child_job_1  {:child_field_1 2}
                               :child_job_2  {:child_field_2 3}}})

  ((wrap-merge-dependency-results identity) {:request_body {:parent_field 1}
                                             :dependencies []})
  => {:request_body {:parent_field 1}
      :dependencies []})

(fact "Merging dependency results with no dependencies"
  ((wrap-merge-dependency-results identity) {:request_body {:parent_field 1}
                                             :dependencies []})
  => {:request_body {:parent_field 1}
      :dependencies []})

(fact "Merging multiple dependency results of the same type"
  ((wrap-merge-dependency-results identity :merge-multiple [:same_job_type])
   {:request_body {:parent_field 1}
    :dependencies [{:job_type    :same_job_type
                    :result_body {:child_field_1 2}}

                   {:job_type    :same_job_type
                    :result_body {:child_field_2 3}}]})
  => (contains {:request_body {:parent_field  1
                               :same_job_type [{:child_field_1 2}
                                               {:child_field_2 3}]}}))

(fact "Adding missing dependencies"
  (let [dependency-builders [[:child_1_type #(job-req {:child_1_field (:parent_field %)} :child_1_type)]
                             [:child_2_type #(job-req {:child_2_field (:parent_field %)} :child_2_type)]]
        handler (fn [job] (success {:parent_result (get-in job [:request_body :parent_field])}))
        decorated (wrap-check-dependencies handler dependency-builders)]

    (decorated {:request_body {:parent_field 123}})
    => (just {:outcome      :more-work
              :dependencies (contains [(job-req {:child_1_field 123} :child_1_type)
                                       (job-req {:child_2_field 123} :child_2_type)]
                                      :in-any-order)})

    ; All dependencies exist
    (decorated {:request_body {:parent_field 123
                               :child_1_type {}
                               :child_2_type {}}})
    => {:outcome :success
        :result  {:parent_result 123}}
    ))

(fact "wrap-http-request"
  (let [echo-body (fn [{body :body}]
                    (update (json/decode body) :headers #(map-keys % name)))
        stop (run-server echo-body {:port 4455})]
    (try
      (let [wrapped-handler (-> identity
                        (wrap-http-request))]

        (fact "decodes json responses"
          (wrapped-handler {:url  "http://localhost:4455"
                    :method :get
                    :body (json/encode {:status  200
                                        :headers {"Content-Type" "application/json; "}
                                        :body    (json/encode {:a 123})})})
          => (matches {:outcome :success
                       :result  {:response {:body {:a 123}}}}))

        (fact "encodes request body as json if it is a map"
          (wrapped-handler {:url  "http://localhost:4455"
                    :body {:status  200
                           :headers {"Content-Type" "application/json; "}
                           :body    (json/encode {:a 123})}})
          => (matches {:outcome :success
                       :result  {:response {:body {:a 123}}}}))

        (fact "throws useful exception if response isn't acceptable"
          (let [handler (-> identity
                            (wrap-http-request :acceptable-response? (constantly false))
                            (wrap-exception-handling))]
            (handler {:url  "http://localhost:4455"
                      :body {:status  200
                             :headers {"Content-Type" "application/json; "}
                             :body    (json/encode {:a 123})}})
            => (matches {:outcome :error
                         :error  {:ex_data {:response {:body {:a 123}}}}}))))
      (finally
        (stop)))))
