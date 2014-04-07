(ns toshtogo.test.functional.fungibility-group-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.client.protocol :refer :all]
            ))

(facts "When requesting more work we may specify :fungibility_group, if a job exists with the same type and request_body as the requested dependency, this will be used instead of creating a new job"
       (let [job-id            (uuid)
             child-job-id      (uuid)
             fungibility-group-id (uuid)
             parent-job-type   (uuid-str)
             child-job-type    (uuid-str)
             child-job-request {:some_request_data 1234}]

         (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))
         (put-job! client child-job-id (-> (job-req child-job-request child-job-type)
                                           (fungibility-group fungibility-group-id)))

         (let [add-deps (fn [job]
                          (add-dependencies
                            (-> (job-req child-job-request child-job-type)
                                (fungibility-group fungibility-group-id))))
               complete-child (fn [job] (success (job :request_body)))]

           @(do-work! client parent-job-type add-deps) => truthy

           (fact "Parent job is not ready until new dependencies complete"
                 (request-work! client parent-job-type) => nil)

           @(do-work! client child-job-type complete-child) => truthy

           (fact (str "Parent job is released when dependencies are complete, "
                      "with dependency responses returned with the job")
                 (let [contract (request-work! client parent-job-type)]
                   contract
                   => (contains {:request_body {:parent-job "parent job"}})

                   (contract :dependencies)
                   => (contains [(contains {:result_body child-job-request})]))))))

(facts "If we specify :fungibility_group, jobs which have been completed will also be matched"
       (let [job-id            (uuid)
             child-job-id      (uuid)
             fungibility-group-id (uuid)
             parent-job-type   (uuid-str)
             child-job-type    (uuid-str)
             child-job-request {:some_request_data 1234}]

         (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))
         (put-job! client child-job-id (-> (job-req child-job-request child-job-type)
                                           (fungibility-group fungibility-group-id)))
         ; Matching child job has already completed
         @(do-work! client child-job-type (fn [job] (success (job :request_body))))
         => truthy

         (let [add-deps (fn [job]
                          (add-dependencies
                            (-> (job-req child-job-request child-job-type)
                                (fungibility-group fungibility-group-id))))]

           @(do-work! client parent-job-type add-deps)
           => truthy

           (fact "Parent job is ready immediately, because a matching job has already completed"
                 (let [contract (request-work! client parent-job-type)]
                   contract
                   => (contains {:request_body {:parent-job "parent job"}})

                   (contract :dependencies)
                   => (contains [(contains {:result_body child-job-request})]))))))
