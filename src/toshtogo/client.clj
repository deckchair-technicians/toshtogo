(ns toshtogo.client
  (:require [toshtogo.util.core :refer [uuid]]
            [toshtogo.api :refer [success error]]
            [toshtogo.client.senders :refer :all]
            [toshtogo.client.http :refer :all]))

(defn job-req
  ([body tags]
     {:tags tags
      :request_body body})
  ([body tags dependencies]
     (assoc (job-req body tags) :dependencies dependencies)))

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (request-work! [this tags])
  (complete-work! [this commitment-id result])
  (do-work! [this tags f]))

(deftype SenderClient [sender]
  Client
  (put-job! [this job-id job-req]
    (PUT! sender
          (str "/api/jobs/" job-id)
          job-req))

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

(defn http-sender-client [base-path]
  (SenderClient. (http-sender base-path)))
