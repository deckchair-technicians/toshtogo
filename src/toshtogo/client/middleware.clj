(ns toshtogo.client.middleware
  (:require [toshtogo.client.util :refer [merge-dependency-results]]
            [toshtogo.client.protocol :refer :all]))

(defn wrap-merge-dependency-results
  "Merges the :result_body from all :dependencies into the \n
  :request_body of the job and assigns it to :combined_request.\n
  \n
  Useful for making toshtogo agents agnostic to whether dependencies are provided\n
  directly in the :request_body, or by dependent jobs."
  [handler & {:keys [merge-multiple] :or {merge-multiple []}}]
  (fn [job]
    (handler (assoc job :request_body (merge-dependency-results job :merge-multiple merge-multiple)))))

(defn check-dependency
  [request missing-dependency-job-reqs [check? job-req-builder]]
  (if (check? request)
    missing-dependency-job-reqs
    (conj missing-dependency-job-reqs (job-req-builder request))))

(defn missing-dependencies [request dependencies]
  (reduce (partial check-dependency request) nil dependencies))

(defn wrap-check-dependencies
  "Takes a handler and a list of [check? dependency-fn] pairs.\n
  \n
  If job :request_body fails any check?, the middleware\n
  will return an add-dependencies result with a list of job-reqs\n
  built by calling (dependency-fn (job :request_body)"

  [handler dependency-builders]
  (fn [job]
    (if-let [missing-dependency-jobs (missing-dependencies (job :request_body) dependency-builders)]
      (apply add-dependencies missing-dependency-jobs)
      (handler job))))