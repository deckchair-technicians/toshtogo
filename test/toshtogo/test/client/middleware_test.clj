(ns toshtogo.test.client.middleware-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :as t]
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

(facts "Optionally retries at given frequency"
  (let [handler (-> (fn [request]
                      (clojure.pprint/pprint request)
                      (clojure.pprint/pprint (:fail request))
                      (if (:fail request)
                        (error {})
                        (success {})))
                    (wrap-try-later)
                    (wrap-extract-request))
        error-request (job-req {:fail  true
                                :retry {:until         (t/date-time 2014 8 8 6 0)
                                        :every-minutes 10}}
                               :some_job)
        succesful-request (job-req {:fail  false
                                    :retry {:until         (t/date-time 2014 8 8 6 0)
                                            :every-minutes 10}}
                                   :some_job)]
    (fact "Returns successful requests"
      (handler succesful-request)
      => (matches (success {}))

      (fact "On error, returns a try-later if we're before cutoff"
        (handler error-request)
        => (matches (try-later (t/date-time 2014 8 8 5 10)))
        (provided (t/now) => (t/date-time 2014 8 8 5 0)))

      (fact "Returns the error after a certain time"
        (handler error-request)
        => (matches (error {}))
        (provided (t/now) => (t/date-time 2014 8 8 6 1))))))
