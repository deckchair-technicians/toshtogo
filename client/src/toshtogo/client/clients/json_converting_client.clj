(ns toshtogo.client.clients.json-converting-client
  (:require [flatland.useful.map :refer [update-each]]
            [toshtogo.util.core :refer [uuid cause-trace parse-datetime]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.client.senders.protocol :refer :all]))

(defn convert-job [job]
  (when job
    (-> job
        (update-each [:contract_created :contract_claimed :contract_due :contract_finished :job_created :last_heartbeat] parse-datetime)
        (update-each [:home_tree_id :commitment_id :contract_id :job_id :requesting_agent :commitment_agent] uuid)
        (update-each [:outcome] keyword))))

(defn convert-link [link]
  (update-each link [:parent_job_id :child_job_id] uuid))

(defn convert-heartbeat [heartbeat]
  (update heartbeat :instruction keyword))

(defn json-converting-client
  [decorated]
  (reify
    Client
    (put-job! [this job-id job-req]
      (convert-job (put-job! decorated job-id job-req)))

    (get-job [this job-id]
      (convert-job (get-job decorated job-id)))

    (get-jobs [this query]
      (-> (get-jobs decorated query)
          (update :data #(map convert-job %))))

    (get-tree [this tree-id]
      (-> (get-tree decorated tree-id)
          (update :root_job convert-job)
          (update :jobs #(map convert-job %))
          (update :links #(map convert-link %))))

    (get-job-types [this]
      (map keyword (get-job-types decorated)))

    (pause-job! [this job-id]
      (pause-job! decorated job-id))

    (retry-job! [this job-id]
      (retry-job! decorated job-id))

    (request-work! [this job-type-or-query]
      (convert-job (request-work! decorated job-type-or-query)))

    (heartbeat! [this commitment-id]
      (convert-heartbeat (heartbeat! decorated commitment-id)))

    (complete-work! [this commitment-id result]
      (convert-job (complete-work! decorated commitment-id result)))))
