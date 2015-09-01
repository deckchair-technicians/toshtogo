(ns toshtogo.test.util.preprocessing_test
  (:import (java.util UUID))
  (:require [midje.sweet :refer :all]
            [toshtogo.util.core :refer [uuid]]
            [toshtogo.server.preprocessing :refer :all]))

(defn is-uuid? [x] (instance? UUID x))

(fact "normalise-job-tree sets job_ids"
      (normalise-job-tree {:job          "1"
                           :dependencies [{:job "1.1"}
                                          {:job          "1.2"
                                           :dependencies [{:job "1.2.1"}]}]}
                          ..agent-id..)
      => (contains {:job_id       is-uuid?
                    :job          "1"
                    :dependencies (just
                                    (contains {:job_id is-uuid?
                                               :job    "1.1"})
                                    (contains {:job_id       is-uuid?
                                               :job          "1.2"
                                               :dependencies (just
                                                               (contains {:job_id is-uuid?
                                                                          :job    "1.2.1"}))})
                                    :in-any-order)}))

(fact "normalise-job-tree sets agent_ids"
      (normalise-job-tree {:job          "1"
                           :dependencies [{:job "1.1"}
                                          {:job          "1.2"
                                           :dependencies [{:job "1.2.1"}]}]}
                          ..agent-id..)
      => (contains {:job              "1"
                    :requesting_agent ..agent-id..
                    :dependencies     (just
                                        (contains {:requesting_agent ..agent-id..
                                                   :job              "1.1"})
                                        (contains {:requesting_agent ..agent-id..
                                                   :job              "1.2"
                                                   :dependencies     (just
                                                                       (contains {:requesting_agent ..agent-id..
                                                                                  :job              "1.2.1"}))})
                                        :in-any-order)}))

(fact "normalise-job-tree supports :fungible_under_parent"
      (let [f-group (uuid)]
        (normalise-job-tree {:job          "1"
                             :dependencies [{:job                  "1.1"
                                             :fungibility_group_id f-group
                                             :dependencies         [{:fungible_under_parent true
                                                                     :job                   "1.1.1"}]}]}
                            ..agent-id..)
        => (contains {:job          "1"
                      :dependencies (just
                                      (contains {:job                  "1.1"
                                                 :fungibility_group_id f-group
                                                 :dependencies         (just
                                                                         (contains {:job                  "1.1.1"
                                                                                    :fungibility_group_id f-group}))}))})))

(fact "normalise-job-tree propagates :home_tree_id"
      (let [tree-id (uuid)]
        (normalise-job-tree {:home_tree_id tree-id
                             :job          "1"
                             :dependencies [{:job          "1.1"
                                             :dependencies [{:job "1.1.1"}]}]}
                            ..agent-id..)
        => (contains {:job          "1"
                      :dependencies (just
                                      (contains {:job          "1.1"
                                                 :home_tree_id tree-id
                                                 :dependencies (just
                                                                 (contains {:job          "1.1.1"
                                                                            :home_tree_id tree-id}))}))})))

(fact "normalise-job-tree sets :parent_job_id"
      (normalise-job-tree {:job_id       "1"
                           :dependencies [{:job_id       "1.1"
                                           :dependencies [{:job_id "1.1.1"}]}]}
                          ..agent-id..)
      => (contains {:job_id       "1"
                    :dependencies (just [(contains {:job_id        "1.1"
                                                    :parent_job_id "1"
                                                    :dependencies  (just [(contains {:job_id        "1.1.1"
                                                                                     :parent_job_id "1.1"})])})])}))
