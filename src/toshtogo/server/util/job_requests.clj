(ns toshtogo.server.util.job-requests
  (:require [flatland.useful.map :refer [update]]
    [toshtogo.util.core :refer [uuid ppstr]]))

(defn assign-job-id [job]
  (if (:job_id job)
    job
    (assoc job :job_id (uuid))))

(defn flattened-dependencies
  "Given a job with :dependencies, returns a list of jobs with
   :parent_job_id set correctly. Assigns :job_id to any jobs
   which do not have one"
  [job]
  (assert (:job_id job) (str "no :job_id on" (ppstr job)))
  (let [dependencies (map assign-job-id (job :dependencies))]
    (concat
      (map (fn [dep] (-> dep (assoc :parent_job_id (job :job_id)) (dissoc :dependencies)))
           dependencies)
      (mapcat flattened-dependencies dependencies))))