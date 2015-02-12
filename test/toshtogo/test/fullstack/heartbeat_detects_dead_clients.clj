(ns toshtogo.test.fullstack.heartbeat-detects-dead-clients
  (:require [midje.sweet :refer :all]

            [toshtogo.test.functional.test-support :refer [test-client in-process available-port]]

            [toshtogo.client
             [protocol :refer :all]]

            [toshtogo.util.core :refer [uuid-str uuid]]

            [toshtogo.server
             [core :refer [start! dev-app dev-db]]]

            [toshtogo.server.heartbeat
             [core :refer [pool check-heartbeats!]]]))

(facts "Client outage detection"
  (let [job-id     (uuid)
        job-type   (uuid-str)
        app-client (test-client :client-config in-process :timeout nil :should-retry false)
        commitment (atom nil)]

    (fact "Job created ok"
      (put-job! app-client job-id (job-req {} job-type))
      => truthy)

    (fact "Requested work ok"
      (reset! commitment (request-work! app-client {:job_id job-id}))
      => truthy)

    (fact "Job is running"
      (get-job app-client job-id)
      => (contains {:outcome :running}))

    (let [port              (available-port)
          http-client-error (promise)
          http-client       (test-client :client-config {:type :http :base-url (str "http://localhost:" port)}
                                         :error-fn (fn [e]
                                                     (deliver http-client-error e))
                                         :timeout nil)
          stop-server       (start! false port false)]

      (fact "Job is running - http"
        (get-job http-client job-id)
        => (contains {:outcome :running}))

      (println "Monitoring start")

      (Thread/sleep 1100)         ;Just a little longer than a heartbeat

      (check-heartbeats! http-client 1)

      (fact "Job is ready for work - http"
        (get-job http-client job-id)
        => (contains {:outcome :waiting}))

      (println "Monitoring stop")
      (.shutdown pool)
      (stop-server))))
