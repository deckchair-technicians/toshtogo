(ns toshtogo.test.client.middleware
  (:require [midje.sweet :refer :all]
            [toshtogo.client.middleware :refer :all]
            [toshtogo.client.protocol :refer :all]))

(fact "Merging child jobs"
      ((wrap-merge-child-jobs identity) {:request_body {:parent_field 1}
                                         :dependencies [{:job_type    :child_job_1
                                                         :result_body {:child_field_1 2}}

                                                        {:job_type    :child_job_2
                                                         :result_body {:child_field_2 3}}]})
      => (contains {:request_body     {:parent_field 1}
                    :combined_request {:parent_field 1
                                       :child_job_1  {:child_field_1 2}
                                       :child_job_2  {:child_field_2 3}}})

      ((wrap-merge-child-jobs identity) {:request_body {:parent_field 1}
                                         :dependencies []})
      => {:request_body {:parent_field 1}
          :combined_request {:parent_field 1}
          :dependencies []})

(fact "Adding missing dependencies"
      (let [dependency-builders [[:child_1_type #(job-req {:child_1_field (:parent_field %)} :child_1_type)]
                                 [:child_2_type #(job-req {:child_2_field (:parent_field %)} :child_2_type)]]
            handler             (fn [job] (success {:parent_result (get-in job [:request_body :parent_field])}))
            decorated           (wrap-check-dependencies handler dependency-builders)]

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
            :result {:parent_result 123}}
        ))
