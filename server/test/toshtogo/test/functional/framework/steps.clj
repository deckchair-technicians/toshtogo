(ns toshtogo.test.functional.framework.steps
  (:require [midje.sweet :refer :all]
            [toshtogo.client.agent :as agent]
            [toshtogo.client.protocol :as client]
            [toshtogo.test.functional.framework.runner :as runner]
            [toshtogo.test.functional.framework.test-ids :as ids]
            [toshtogo.test.functional.test-support :as ts]))

(defn stop-service []
  (fn [{:keys [service] :as container}]
    (println "Stopping service")
    (deref (future (agent/stop service)) 100 "didn't stop")
    => #(not= "didn't stop" %)
    container))

(defn agent-is-running [job-type handler]
  (fn [{:keys [service] :as container}]
    (println "Starting service")
    (when service (agent/stop service))

    (let [service (agent/start-service (agent/job-consumer
                                   (constantly ts/client)
                                   job-type
                                   handler
                                   :sleep-on-no-work-ms 1))]
      (-> container
          (assoc :service service)
          (runner/add-cleanup (stop-service))))))


(defn put-a-job [job-id job-req]
  (fn [{:keys [client] :as container}]
    (println "Putting job" (ids/id-desc job-id)  (str "(" job-id ")"))
    (client/put-job! client job-id job-req)
    container))

(defn wait-for-job-status-matching [pred job-id]
  (fn [{:keys [client] :as container}]
    (println "Waiting for" (ids/id-desc job-id) (str "(" job-id ")"))
    (deref (future (while (-> (client/get-job client job-id)
                              :outcome
                              ((comp not pred)))
                     (Thread/sleep 100)))
           2000 nil)

    container))

(defn wait-for-job-status [expected-state job-id]
  (wait-for-job-status-matching #(= expected-state %) job-id))

(defn job-state [job-id]
  (fn [{:keys [client]}]
    (println "Getting job" (ids/id-desc job-id) (str "(" job-id ")"))
    (client/get-job client job-id)))


(defn wait-millis [millis]
  (fn [container]
    (Thread/sleep millis)
    container))