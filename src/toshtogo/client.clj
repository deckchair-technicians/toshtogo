(ns toshtogo.client
  (:require [toshtogo.util :refer [uuid]]
            [toshtogo.senders :refer :all]))

(defprotocol Client
  (put-job! [this job-id job])
  (request-work! [this tags]))

(deftype SenderClient [sender]
  Client
  (put-job! [this job-id job]
    (PUT! sender
     (str "/api/jobs/" job-id)
     job))

  (request-work! [this tags]
    (PUT! sender
     "/api/commitments"
     {:commitment_id (uuid)
      :tags tags})))

(defn app-sender-client [app]
  (SenderClient. (app-sender app)))
