(ns toshtogo.client.protocol)

(defn success [response-body]
  {:outcome :success
   :result  response-body})

(defn error [error-text]
  {:outcome :error
   :error   error-text})

(defn cancelled []
  {:outcome :cancelled})

(defn add-dependencies [dependency & dependencies]
  {:outcome      :more-work
   :dependencies (concat [dependency] dependencies)})

(defn try-later
  ([contract-due]
   {:outcome       :try-later
    :contract_due contract-due})
  ([contract-due error-text]
   (assoc (try-later contract-due)
     :error error-text)))

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (pause-job! [this job-id])
  (request-work! [this tags])
  (heartbeat! [this commitment-id])
  (complete-work! [this commitment-id result])
  (do-work! [this tags f]))

