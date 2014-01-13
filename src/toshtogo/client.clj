(ns toshtogo.client
  (:require [toshtogo.util.core :refer [uuid]]
            [toshtogo.api :refer [success error]]
            [toshtogo.client.senders :refer :all]))

(defn job-map
  ([body tags]
     {:tags tags
       :request_body body})
  ([job-id body tags]
      (assoc (job-map body tags) :job_id job-id)))

(defprotocol Client
  (put-job! [this job-id job])
  (get-job [this job-id])
  (request-work! [this tags])
  (complete-work! [this commitment-id result])
  (do-work! [this tags f]))

(deftype SenderClient [sender]
  Client
  (put-job! [this job-id job]
    (PUT! sender
          (str "/api/jobs/" job-id)
          job))

  (get-job [this job-id]
    (GET sender (str "/api/jobs/" job-id)))

  (request-work! [this tags]
    (PUT! sender
          "/api/commitments"
          {:commitment_id (uuid)
           :tags tags}))

  (complete-work! [this commitment-id result]
    (PUT! sender
          (str "/api/commitments/" commitment-id)
          result))

  (do-work! [this tags f]
    (future
      (when-let [contract (request-work! this tags)]
        (let [result (f contract)]
          (complete-work! this (contract :commitment_id) result)
          {:contract contract
           :result   result})))))

(defn app-sender-client [app]
  (SenderClient. (app-sender app)))
