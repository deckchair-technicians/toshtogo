(ns toshtogo.client.protocol)

(defn job-req
  ([body tags]
   {:tags tags
    :request_body body})
  ([body tags dependencies]
   (assoc (job-req body tags) :dependencies dependencies)))

(def success             toshtogo.api/success)
(def error               toshtogo.api/error)
(def cancelled           toshtogo.api/cancelled)
(def add-dependencies    toshtogo.api/add-dependencies)
(def try-later           toshtogo.api/try-later)

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (pause-job! [this job-id])
  (request-work! [this tags])
  (heartbeat! [this commitment-id])
  (complete-work! [this commitment-id result])
  (do-work! [this tags f]))

