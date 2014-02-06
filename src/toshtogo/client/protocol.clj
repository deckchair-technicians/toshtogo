(ns toshtogo.client.protocol
  (:require [toshtogo.util.core :refer [uuid cause-trace]]
            [toshtogo.server.api.protocol :as server-protocol]))

(defn job-req
  ([body tags]
   {:tags         tags
    :request_body body})
  ([body tags dependencies]
   (assoc (job-req body tags) :dependencies dependencies)))

(def success             server-protocol/success)
(def error               server-protocol/error)
(def cancelled           server-protocol/cancelled)
(def add-dependencies    server-protocol/add-dependencies)
(def try-later           server-protocol/try-later)

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (pause-job! [this job-id])

  (request-work! [this tags])
  (heartbeat! [this commitment-id])
  (complete-work! [this commitment-id result]))

(def heartbeat-time 1000)

(defn with-exception-handling
  [heartbeat! f contract]
  (try
    (let [work-future (future (f contract))]
      (doseq [done? (take-while false? (repeatedly (fn [] (or (future-cancelled? work-future)
                                                              (future-done? work-future)))))]
        (Thread/sleep heartbeat-time)
        (when (= "cancel" (:instruction (heartbeat! contract))) (future-cancel work-future)))
      @work-future)
    (catch Throwable t
      (error (cause-trace t)))))

(defn do-work! [client tags f]
  (future
    (when-let [contract (request-work! client tags)]
      (let [result (with-exception-handling (fn [c] (heartbeat! client (c :commitment_id))) f contract)]
        (complete-work! client (contract :commitment_id) result)
        {:contract contract
         :result   result}))))

