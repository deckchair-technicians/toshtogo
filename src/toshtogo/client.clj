(ns toshtogo.client
  (:require [toshtogo.util :refer [uuid]]
            [toshtogo.senders :refer :all]))

(defn success [response-body]
  {:outcome :success
   :result  response-body})

(defprotocol Client
  (put-job! [this job-id job])
  (request-work! [this tags])
  (do-work! [this tags f]))

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
           :tags tags}))

  (do-work! [this tags f]
    (future
      (when-let [contract (request-work! this tags)]
        (let [result (f contract)]
          {:contract contract
           :result   result})
        ))))

(defn app-sender-client [app]
  (SenderClient. (app-sender app)))
