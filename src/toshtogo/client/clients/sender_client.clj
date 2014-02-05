(ns toshtogo.client.clients.sender-client
  (:require [clj-time.format :as tf]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [uuid cause-trace]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.client.senders.protocol :refer :all]))

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

(defn sender-client [sender]
  (reify
    Client
    (put-job! [this job-id job-req]
      (PUT! sender
            (str "/api/jobs/" job-id)
            job-req))

    (get-job [this job-id]
      (update (GET sender (str "/api/jobs/" job-id)) :last_heartbeat #(when % (tf/parse (tf/formatters :date-time) %))))

    (pause-job! [this job-id]
      (POST! sender
             (str "/api/jobs/" job-id "/pause")
             nil))

    (request-work! [this tags]
      (PUT! sender
            "/api/commitments"
            {:commitment_id (uuid)
             :tags          tags}))

    (complete-work! [this commitment-id result]
      (PUT! sender
            (str "/api/commitments/" commitment-id)
            result))

    (heartbeat! [this commitment-id]
      (POST! sender (str "/api/commitments/" commitment-id "/heartbeat") {}))

    (do-work! [this tags f]
      (future
        (when-let [contract (request-work! this tags)]
          (let [result (with-exception-handling (fn [c] (heartbeat! this (c :commitment_id))) f contract)]
            (complete-work! this (contract :commitment_id) result)
            {:contract contract
             :result   result}))))))