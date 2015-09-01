(ns toshtogo.server.persistence.logging-persistence
  (:require [toshtogo.server.persistence
             [protocol :refer :all]]

            [toshtogo.server
             [logging :refer :all]]

            [bowen.core :refer [defrecord-decorator]]))


(defrecord-decorator LoggingPersistence
  [decorated-persistence logger]

  Persistence
  (insert-jobs!
   [this jobs]

   (doseq [ev (map new-job-event jobs)]
     (log logger ev))

   (insert-jobs! decorated-persistence jobs))

  (insert-commitment!
   [this commitment-id contract agent-details]

   (when (insert-commitment! decorated-persistence commitment-id contract agent-details)
     (log logger (commitment-started-event commitment-id contract agent-details))))

  (insert-result!
   [this commitment-id result agent-details]
   (let [contract (get-contract decorated-persistence {:commitment_id commitment-id})]

     (log logger (commitment-result-event contract agent-details result))

     (insert-result! decorated-persistence commitment-id result agent-details))))

(defn logging-persistence
  [decorated-persistence logger]
  (->LoggingPersistence decorated-persistence logger))
