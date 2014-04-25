(ns toshtogo.test.functional.fungibility-group-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.util.deterministic-representation :refer [deterministic-representation]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.server.core :refer [dev-db]]))

(background (before :contents @migrated-dev-db))

(def complex-request {:this "request"
                      :is {:fairly ["complex" 1 2 123]
                           :to "try to"
                           :make_sure [{:we 345435
                                        :deterministically ["represent" "the"]
                                        :request :when
                                        :checking {:fungibility 1 :z 1 :a 1}}
                                       10
                                       9
                                       8
                                       3
                                       ]}
                      123 "a"
                      })

(facts (str "When requesting more work we may specify :fungibility_group_id, if a job exists "
            "with the same type and request_body as the requested dependency, this will be "
            "used instead of creating a new job")

       (let [job-id               (uuid)
        child-job-id         (uuid)
        fungibility-group-id (uuid)
        parent-job-type      (uuid-str)
        child-job-type       (uuid-str)
        child-job-request    complex-request]

    (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))
    (put-job! client child-job-id (-> (job-req child-job-request child-job-type)
                                      (fungibility-group fungibility-group-id)))

    (let [add-deps (fn [job]
                     (add-dependencies
                      (-> (job-req child-job-request child-job-type)
                          (fungibility-group fungibility-group-id))))]

      @(do-work! client parent-job-type add-deps) => truthy

      (fact "Parent job is not ready until new dependencies complete"
        (request-work! client parent-job-type) => nil)

      @(do-work! client child-job-type (return-success-with-result {:child "result"})) => truthy

      (fact (str "Parent job is released when dependencies are complete, "
                 "with dependency responses returned with the job")
        (let [contract (request-work! client parent-job-type)]
          contract
          => (contains {:request_body {:parent-job "parent job"}})

          (contract :dependencies)
          => (contains [(contains {:result_body {:child "result"}})]))))))

(facts "If we specify :fungibility_group_id, jobs which have been completed will also be matched"
  (let [job-id               (uuid)
        child-job-id         (uuid)
        fungibility-group-id (uuid)
        parent-job-type      (uuid-str)
        child-job-type       (uuid-str)
        child-job-request    complex-request]

    (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))
    (put-job! client child-job-id (-> (job-req child-job-request child-job-type)
                                      (fungibility-group fungibility-group-id)))
                                        ; Matching child job has already completed
    @(do-work! client child-job-type (fn [job] (success {:child "result"})))
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
          => (contains [(contains {:result_body {:child "result"}})]))))))

(future-fact "If we specify :fungibility_group_id, other dependencies added at the same time will be matched"
  (let [job-id               (uuid)
        parent-job-type      (uuid-str)
        child-job-type       (uuid-str)
        child-job-request    complex-request]

    (put-job! client job-id (job-req {:parent-job "parent job"} parent-job-type))

    (let [add-deps (fn [job]
                     (add-dependencies
                       (-> (job-req child-job-request child-job-type)
                           (fungible-under-parent))

                       (-> (job-req child-job-request child-job-type)
                           (fungible-under-parent))))]

      @(do-work! client parent-job-type add-deps)
      => truthy

      (fact "Parent job is not ready until new dependencies complete"
            (request-work! client parent-job-type) => nil)

      (fact "One child job has been added"
            @(do-work! client child-job-type return-success)
            => truthy)

      (fact "ONLY one child job has been added"
            @(do-work! client child-job-type return-success)
            => falsey)

      (fact "Parent job is ready when single depdendency completes"
        (let [contract (request-work! client parent-job-type)]
          contract
          => (contains {:request_body {:parent-job "parent job"}}))))))

(facts "Fungibility includes job_type"
  (let [job-id               (uuid)
        fungibility-group-id (uuid)
        child-job-id-1       (uuid)
        child-job-id-2       (uuid)
        parent-job-type      (uuid-str)
        child-job-type-1     (uuid-str)
        child-job-type-2     (uuid-str)
        identical-child-request    complex-request]

    (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))

    (put-job! client child-job-id-1 (-> (job-req identical-child-request child-job-type-1)
                                      (fungibility-group fungibility-group-id)))

    (fact "There is a completed job of type 1"
      @(do-work! client child-job-type-1 return-success)
      => truthy)

    (fact "We add a dependency on a job of type 2 with an identical request and fungibility group"
      @(do-work! client parent-job-type (fn [job]
                                          (add-dependencies
                                            (-> (job-req identical-child-request child-job-type-2)
                                                (fungibility-group fungibility-group-id)))))
      => truthy)

    (fact (str "Child job of different type is NOT matched to job in same fungibility group with identical "
               "request but different job_type")
          @(do-work! client child-job-type-2 return-success)
          => truthy)))
