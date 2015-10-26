(ns toshtogo.test.functional.fungibility-test
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.server.core :refer [dev-db]]))

(background (before :contents @migrated-dev-db))

(defn random-request-that-fungibility-ignores [] {:something (uuid-str)})

(facts (str "When requesting more work we may specify :fungibility_key, if a job exists "
            "with the same type and fungibility_key as the requested dependency, this will be "
            "used instead of creating a new job")

       (let [job-id            (uuid)
             child-job-id      (uuid)
             fungibility-key   (uuid-str)
             parent-job-type   (uuid-str)
             child-job-type    (uuid-str)]

    (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))
  (put-job! client child-job-id (-> (job-req {:child-job "ONE"} child-job-type)
                                      (with-fungibility-key fungibility-key)))

    (let [add-deps (fn [job]
                     (add-dependencies
                      (-> (job-req {:child-job "TWO"} child-job-type)
                          (with-fungibility-key fungibility-key))))]

      @(do-work! client parent-job-type add-deps) => truthy

      (fact "Parent job is not ready until new dependencies complete"
        (request-work! client parent-job-type) => nil)

      (fact "Only one child job exists"
        (:data (get-jobs client {:job_type child-job-type})) => (contains [(contains {:request_body {:child-job "ONE"}})]))

      @(do-work! client child-job-type echo-request) => truthy

      (fact (str "Parent job is released when dependencies are complete, "
                 "with dependency responses returned with the job")
        (let [contract (request-work! client parent-job-type)]
          contract
          => (contains {:request_body {:parent-job "parent job"}})

          (contract :dependencies)
          => (contains [(contains {:result_body {:child-job "ONE"}})]))))))

(facts "If we specify :fungibility_key, jobs which have been completed will also be matched"
  (let [job-id          (uuid)
        child-job-id    (uuid)
        fungibility-key (uuid-str)
        parent-job-type (uuid-str)
        child-job-type  (uuid-str)]

    (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))
    (put-job! client child-job-id (-> (job-req {:child-job "ONE"} child-job-type)
                                      (with-fungibility-key fungibility-key)))
    ; Matching child job has already completed
    @(do-work! client child-job-type echo-request)
    => truthy

    (let [add-deps (fn [job]
                     (add-dependencies
                       (-> (job-req {:child-job "TWO"} child-job-type)
                           (with-fungibility-key fungibility-key))))]

      @(do-work! client parent-job-type add-deps)
      => truthy

      (fact "Parent job is ready immediately, because a matching job has already completed"
        (let [contract (request-work! client parent-job-type)]
          contract
          => (contains {:request_body {:parent-job "parent job"}})

          (contract :dependencies)
          => (contains [(contains {:result_body {:child-job "ONE"}})]))))))

(facts "We support alternative fungibility keys to allow backwards compatibility"
  (let [job-id          (uuid)
        child-job-id    (uuid)
        fungibility-key-v1 (uuid-str)
        fungibility-key-v2 (uuid-str)
        parent-job-type (uuid-str)
        child-job-type  (uuid-str)]

    (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))
    (put-job! client child-job-id (-> (job-req {:child-job "ONE"} child-job-type)
                                      (with-fungibility-key fungibility-key-v1)))
    ; Matching child job has already completed
    @(do-work! client child-job-type echo-request)
    => truthy

    (let [add-deps (fn [job]
                     (add-dependencies
                       (-> (job-req {:child-job "TWO"} child-job-type)
                           (with-fungibility-key fungibility-key-v2)
                           (with-alternative-fungibility-keys [fungibility-key-v1]))))]

      @(do-work! client parent-job-type add-deps)
      => truthy

      (fact "Parent job is ready immediately, because a matching job has already completed"
        (let [contract (request-work! client parent-job-type)]
          contract
          => (contains {:request_body {:parent-job "parent job"}})

          (contract :dependencies)
          => (contains [(contains {:result_body {:child-job "ONE"}})]))))))

(facts "Fungibility includes job_type"
  (let [job-id           (uuid)
        fungibility-key  (uuid-str)
        child-job-id-1   (uuid)
        parent-job-type  (uuid-str)
        child-job-type-1 (uuid-str)
        child-job-type-2 (uuid-str)]

    (put-job! client job-id       (job-req {:parent-job "parent job"} parent-job-type))

    (put-job! client child-job-id-1 (-> (job-req (random-request-that-fungibility-ignores) child-job-type-1)
                                        (with-fungibility-key fungibility-key)))

    (fact "There is a completed job of type 1"
      @(do-work! client child-job-type-1 return-success)
      => truthy)

    (fact "We add a dependency on a job of type 2 with an identical fungibility key"
      @(do-work! client parent-job-type (fn [job]
                                          (add-dependencies
                                            (-> (job-req (random-request-that-fungibility-ignores) child-job-type-2)
                                                (with-fungibility-key fungibility-key)))))
      => truthy)

    (fact "Child job of different type is NOT matched to job with same fungibility key but different job type"
          @(do-work! client child-job-type-2 return-success)
          => truthy)))
