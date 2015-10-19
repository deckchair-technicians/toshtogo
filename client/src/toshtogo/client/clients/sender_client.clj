(ns toshtogo.client.clients.sender-client
  (:require [clj-time.format :as tf]
            [ring.util.codec :refer [form-encode]]
            [clojure.string :as s]
            [flatland.useful.map :refer [update-each]]
            [toshtogo.util.core :refer [uuid safe-name ensure-seq]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.client.senders.protocol :refer :all]))

(defn names [xs]
  (when xs (map name (ensure-seq xs))))

(defn to-query-string [query]
  (-> query
      (update :order-by #(map (fn [order-by]
                               (if (sequential? order-by)
                                 (s/join " " (map safe-name order-by))
                                 (safe-name order-by)))
                             %))
      (update-each [:job_type :outcome :tags :fields] names)
      form-encode))

(defn sender-client [sender]
  (reify
    Client
    (put-job! [this job-id job-req]
      (PUT! sender
            (str "/api/jobs/" job-id)
            job-req))

    (get-job [this job-id]
      (GET sender (str "/api/jobs/" job-id)))

    (get-jobs [this query]
      (GET sender (str "/api/jobs?" (to-query-string query))))

    (get-tree [this tree-id]
      (GET sender (str "/api/trees/" tree-id)))

    (get-job-types [this]
      (GET sender "/api/metadata/job_types"))

    (pause-job! [this job-id]
      (POST! sender
             (str "/api/jobs/" job-id "?action=pause")
             nil))

    (retry-job! [this job-id]
      (POST! sender
             (str "/api/jobs/" job-id "?action=retry")
             nil))

    (request-work! [this job-type-or-query]
      (let [query (if (not (map? job-type-or-query)) {:job_type (str job-type-or-query)} job-type-or-query)]
        (PUT! sender
              "/api/commitments"
              {:commitment_id (uuid)
               :query query})))

    (complete-work! [this commitment-id result]
      (PUT! sender
            (str "/api/commitments/" commitment-id)
            result))

    (heartbeat! [this commitment-id]
      (POST! sender (str "/api/commitments/" commitment-id "/heartbeat") {}))))
