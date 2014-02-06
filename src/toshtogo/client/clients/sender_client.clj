(ns toshtogo.client.clients.sender-client
  (:require [clj-time.format :as tf]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [uuid]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.client.senders.protocol :refer :all]))

(defn sender-client [sender]
  (reify
    Client
    (put-job! [this job-id job-req]
      (PUT! sender
            (str "/api/jobs/" job-id)
            job-req))

    (get-job [this job-id]
      (GET sender (str "/api/jobs/" job-id)))

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
      (POST! sender (str "/api/commitments/" commitment-id "/heartbeat") {}))))