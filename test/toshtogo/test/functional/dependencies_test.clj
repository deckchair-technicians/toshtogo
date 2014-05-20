(ns toshtogo.test.functional.dependencies-test
  (:import (java.util UUID)
           (toshtogo.client.senders SenderException))
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]))

(background (before :contents @migrated-dev-db))

(facts "Jobs can have dependencies"
       (let [job-id (uuid)
             child-one-id (uuid)
             child-two-id (uuid)
             parent-job-type (uuid-str)
             child-job-type (uuid-str)]

         (put-job!
           client
           job-id (job-req
                    {:a "field value"} parent-job-type
                    :dependencies [(-> (job-req {:b "child one"} child-job-type)
                                       (with-job-id child-one-id))
                                   (-> (job-req {:b "child two"} child-job-type)
                                       (with-job-id child-two-id))]))

         (fact "No contract is created for parent job"
               (request-work! client parent-job-type) => nil)

         (fact "Dependencies are in the same job tree"
               (let [parent-job-tree-id (:home_tree_id (get-job client job-id))]
                 (get-job client child-one-id) => (contains {:home_tree_id parent-job-tree-id})
                 (get-job client child-two-id) => (contains {:home_tree_id parent-job-tree-id})))

         (:contract @(do-work! client {:job_id child-one-id} (return-success-with-result {:child-one "result"})))
         => (contains {:request_body {:b "child one"}})

         (fact "Parent job is not ready until all dependencies complete"
               (request-work! client parent-job-type) => nil
               (get-job client job-id) => (contains {:outcome :waiting}))

         @(do-work! client child-job-type (return-success-with-result {:child-two "result"}))

         (fact (str "Parent job is released when dependencies are complete, "
                    "with dependency responses merged into its request")
               (let [contract (request-work! client parent-job-type)]
                 contract
                 => (contains {:request_body {:a "field value"}})

                 (contract :dependencies)
                 => (contains [(contains {:result_body {:child-one "result"}})
                               (contains {:result_body {:child-two "result"}})]
                              :in-any-order)))))

(facts "Agents can respond by requesting more work before the job is executed"
       (let [job-id (uuid)
             child-one-id (uuid)
             child-two-id (uuid)
             parent-job-type (uuid-str)
             child-job-type (uuid-str)]

         (put-job! client job-id (job-req {:parent-job "parent job"} parent-job-type))

         (let [add-deps (fn [job]
                          (add-dependencies
                            (-> (job-req {:first-dep "first dep"} child-job-type)
                                (with-job-id child-one-id))
                            (-> (job-req {:second-dep "second dep"} child-job-type)
                                (with-job-id child-two-id))))
               complete-child (fn [job] (success (job :request_body)))]

           @(do-work! client parent-job-type add-deps) => truthy

           (fact "Parent job is not ready until new dependencies complete"
                 (request-work! client parent-job-type) => nil)

           (fact "Dependencies are in the same job tree"
                 (let [parent-job-tree-id (:home_tree_id (get-job client job-id))]
                   (get-job client child-one-id) => (contains {:home_tree_id parent-job-tree-id})
                   (get-job client child-two-id) => (contains {:home_tree_id parent-job-tree-id})))

           @(do-work! client child-job-type complete-child) => truthy
           @(do-work! client child-job-type complete-child) => truthy

           (fact (str "Parent job is released when dependencies are complete, "
                      "with dependency responses included in the job")
                 (let [contract (request-work! client parent-job-type)]
                   contract
                   => (contains {:request_body {:parent-job "parent job"}})

                   (:dependencies contract)
                   => (contains [(contains {:result_body {:first-dep "first dep"}})
                                 (contains {:result_body {:second-dep "second dep"}})]
                                :in-any-order))))))

(facts "Jobs can have additional dependencies beyond their children"
       (let [parent-job-id (uuid)
             other-job-id (uuid)
             parent-job-type (uuid-str)
             other-job-type (uuid-str)]

         (put-job! client other-job-id (job-req {:some-other-job "other job"} other-job-type))

         (put-job! client parent-job-id (-> (job-req {:parent-job "parent job"} parent-job-type)
                                            (with-dependency-on other-job-id)))

         (fact "Parent job is not ready until dependency completes"
               (request-work! client parent-job-type)
               => nil)

         @(do-work! client other-job-type return-success)
         => truthy

         (fact (str "Parent job is released when dependencies are complete, "
                    "with dependency responses included in the job")
               (let [contract (request-work! client parent-job-type)]
                 contract
                 => (contains {:request_body {:parent-job "parent job"}})

                 (:dependencies contract)
                 => (contains [(contains {:request_body {:some-other-job "other job"}})])))))

(facts "Can explicitly set job_id on dependencies"
       (let [parent-job-id (uuid)
             child-job-id (uuid)
             parent-job-type (uuid-str)
             child-job-type (uuid-str)]

         (put-job! client parent-job-id (-> (job-req {:parent-job "parent job"} parent-job-type)
                                            (with-dependencies [(-> (job-req {:child-job "child job"} child-job-type)
                                                                    (with-job-id child-job-id))])))

         (get-job client child-job-id)
         => (contains {:request_body {:child-job "child job"}})))

(facts "Agents can respond by adding a dependency on an existing job"
       (let [parent-job-id (uuid)
             other-job-id (uuid)
             parent-job-type (uuid-str)
             other-job-type (uuid-str)]

         (put-job! client other-job-id (job-req {:some-other-job "other job"} other-job-type))
         (put-job! client parent-job-id (job-req {:parent-job "parent job"} parent-job-type))

         @(do-work! client parent-job-type (fn [job] (add-dependencies other-job-id)))
         => truthy

         (fact "Other job is now included in the parent job's home tree"
               (let [parent-job-tree-id (:home_tree_id (get-job client parent-job-id))]
                 (:data (get-jobs client {:tree_id parent-job-tree-id}))
                 => (contains [(contains {:job_id other-job-id})])))

         (fact "Parent job is not ready until new dependencies complete"
               (request-work! client parent-job-type) => nil)

         @(do-work! client other-job-type return-success) => truthy

         (fact (str "Parent job is released when dependencies are complete, "
                    "with dependency responses included in the job")
               (let [contract (request-work! client parent-job-type)]
                 contract
                 => (contains {:request_body {:parent-job "parent job"}})

                 (:dependencies contract)
                 => (contains [(contains {:request_body {:some-other-job "other job"}})])))))