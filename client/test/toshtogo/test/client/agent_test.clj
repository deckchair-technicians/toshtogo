(ns toshtogo.test.client.agent-test
  (:require [midje.sweet :refer :all]
            [toshtogo.client.agent :refer :all]))

(fact "->dispatch-handler"
  (let [handler (->dispatch-handler {:type-a (constantly :a-handler-response)
                                     :type-b (constantly :b-handler-response)})]
    (fact "routes type-a job successfully"
      (handler {:job_type "type-a"}) => :a-handler-response)

    (fact "routes type-b job successfully"
      (handler {:job_type "type-b"}) => :b-handler-response)))
