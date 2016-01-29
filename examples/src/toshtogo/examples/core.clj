(ns toshtogo.examples.core
  (:require [toshtogo.client
             [agent :refer :all]
             [core :refer [client]]
             [protocol :as tt]
             [util :refer [agent-details]]]
            [toshtogo.examples.long-running :as long-running])
  (:gen-class)
  (:import (java.util UUID)))

(def handlers
  {:long-running long-running/handler})

(defn -main []
  (let [cl (client {:type          :http
                    :base-url      "http://localhost:3001"}
                   :error-fn      clojure.stacktrace/print-cause-trace
                   :agent-details (agent-details "savagematt.toshtogo" "examples"))]

    (tt/put-job! cl
                 (UUID/randomUUID)
                 (tt/job-req {:run-for-ms       5000
                              :dependency-count 3
                              :depth            3}
                             :long-running))

    (start-service (job-consumer (constantly cl)
                                 {:job_type (keys handlers)}
                                 (->dispatch-handler handlers))
                   :thread-count 3)))