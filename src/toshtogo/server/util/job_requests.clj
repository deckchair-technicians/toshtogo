(ns toshtogo.server.util.job-requests
  (:require [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [uuid ppstr]]))

(defn assign-job-id [job]
  (if (:job_id job)
    job
    (assoc job :job_id (uuid))))

(defn set-fungibility-group-id [job parent-job]
  (assoc job :fungibility_group_id (or (:fungibility_group_id job)
                                       (when (:fungible_under_parent job)
                                         (:fungibility_group_id parent-job))
                                       (:job_id job))))

(defn normalised-job-list
      "Given a job with :dependencies, returns a list of jobs with
       :parent_job_id set correctly. Assigns :job_id to any jobs
       which do not have one"
  ([root-job] (normalised-job-list nil root-job))
  ([parent-job job]
   (when parent-job
     (assert (:job_id parent-job) (str "no :job_id on" (ppstr parent-job))))

   (let [dependencies (:dependencies job)
         normalised-job (-> job
                            assign-job-id
                            (assoc :parent_job_id (or (:parent_job_id job) (:job_id parent-job)))
                            (set-fungibility-group-id parent-job)
                            (dissoc :dependencies))]
     (concat
       [normalised-job]
       (mapcat (partial normalised-job-list normalised-job) dependencies)))))