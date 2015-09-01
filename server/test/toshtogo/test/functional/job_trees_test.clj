(ns toshtogo.test.functional.job-trees-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer [migrated-dev-db client]]
            [toshtogo.test.midje-schema :refer :all]
            [schema.core :as s])
  (:import (java.util UUID)))

(background (before :contents @migrated-dev-db))

(fact "We can request a whole job tree, which returns links and stripped-down job details to keep the message size small"
      (let [parent-job-id (uuid)
            child-1-job-id (uuid)
            child-2-job-id (uuid)
            grandchild-job-id (uuid)
            job-in-a-different-home-tree (uuid)]

        (put-job! client job-in-a-different-home-tree (-> (job-req {} "job_in_different_home_tree")
                                                          (with-name "e (other job)")))

        (put-job! client parent-job-id (-> (job-req {} "parent_job_type")
                                           (with-dependency-on
                                             job-in-a-different-home-tree)
                                           (with-name "a (parent)")

                                           (with-dependencies
                                             [(-> (job-req {} "child_job_type")
                                                  (with-job-id child-1-job-id)
                                                  (with-name "b (child 1)"))

                                              (-> (job-req {} "child_job_type")
                                                  (with-job-id child-2-job-id)
                                                  (with-name "c (child 2)")

                                                  (with-dependencies (-> (job-req {} "grandchild_job_type")
                                                                         (with-job-id grandchild-job-id)
                                                                         (with-name "d (grandchild)"))))])))

        (let [tree-id (:home_tree_id (get-job client parent-job-id))]
          (:data (get-jobs client {:tree_id tree-id}))
          => (contains [(contains {:job_id job-in-a-different-home-tree})])

          (get-tree client tree-id)
          => (matches {:root_job {:job_id parent-job-id}
                       :jobs     (in-any-order [{:job_id   parent-job-id
                                                 :job_name "a (parent)"
                                                 :job_type "parent_job_type"
                                                 :outcome  :no-contract}

                                                {:job_id   child-1-job-id
                                                 :job_name "b (child 1)"
                                                 :job_type "child_job_type"
                                                 :outcome  :no-contract}

                                                {:job_id   child-2-job-id
                                                 :job_name "c (child 2)"
                                                 :job_type "child_job_type"
                                                 :outcome  :no-contract}

                                                {:job_id   grandchild-job-id
                                                 :job_name "d (grandchild)"
                                                 :job_type "grandchild_job_type"
                                                 :outcome  :no-contract}

                                                {:job_id   job-in-a-different-home-tree
                                                 :job_name "e (other job)"
                                                 :job_type "job_in_different_home_tree"
                                                 :outcome  :no-contract}])


                       :links    (in-any-order [{:parent_job_id parent-job-id
                                                 :child_job_id  child-1-job-id}

                                                {:parent_job_id parent-job-id
                                                 :child_job_id  child-2-job-id}

                                                {:parent_job_id child-2-job-id
                                                 :child_job_id  grandchild-job-id}

                                                {:parent_job_id parent-job-id
                                                 :child_job_id  job-in-a-different-home-tree}])}))))
