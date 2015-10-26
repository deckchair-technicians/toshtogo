(ns toshtogo.test.util.preprocessing_test
  (:import (java.util UUID))
  (:require [midje.sweet :refer :all]
            [toshtogo.util.core :refer [uuid]]
            [toshtogo.server.preprocessing :refer :all]))

(defn is-uuid? [x] (instance? UUID x))

(fact "normalise-job-graph sets job_ids"
      (normalise-job-graph {:job          "1"
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

(fact "normalise-job-graph sets agent_ids"
      (normalise-job-graph {:job          "1"
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

(fact "normalise-job-graph propagates :home_graph_id"
      (let [graph-id (uuid)]
        (normalise-job-graph {:home_graph_id graph-id
                             :job          "1"
                             :dependencies [{:job          "1.1"
                                             :dependencies [{:job "1.1.1"}]}]}
                            ..agent-id..)
        => (contains {:job          "1"
                      :dependencies (just
                                      (contains {:job          "1.1"
                                                 :home_graph_id graph-id
                                                 :dependencies (just
                                                                 (contains {:job          "1.1.1"
                                                                            :home_graph_id graph-id}))}))})))

(fact "normalise-job-graph sets :parent_job_id"
      (normalise-job-graph {:job_id       "1"
                           :dependencies [{:job_id       "1.1"
                                           :dependencies [{:job_id "1.1.1"}]}]}
                          ..agent-id..)
      => (contains {:job_id       "1"
                    :dependencies (just [(contains {:job_id        "1.1"
                                                    :parent_job_id "1"
                                                    :dependencies  (just [(contains {:job_id        "1.1.1"
                                                                                     :parent_job_id "1.1"})])})])}))
