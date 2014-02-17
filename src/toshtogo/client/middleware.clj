(ns toshtogo.client.middleware
  (:require [toshtogo.client.util :refer [merge-child-jobs]]
            [toshtogo.client.protocol :refer :all]))

(defn wrap-merge-child-jobs
  "Merges the :result_body from all :dependencies into the \n
  :request_body of the job and assigns it to :combined_request.\n
  \n
  Useful for making toshtogo agents agnostic to whether dependencies are provided\n
  directly in the :request_body, or by dependent jobs."
  [handler]
  (fn [job]
    (handler (assoc job :combined_request (merge-child-jobs job)))))

(defn check-dependency
  [request missing-jobs [expected-key dep-func]]
  (if (contains? request expected-key)
    missing-jobs
    (conj missing-jobs (dep-func request))))

(defn missing-dependencies [request dependencies]
  (reduce (partial check-dependency request) nil dependencies))

(defn wrap-check-dependencies
  "Takes a handler and a list of [key dependency-fn] pairs.\n
  \n
  If job :request_body is missing any of the keys, the middleware\n
  will return an add-dependencies result with a list of job-reqs\n
  built by calling (dependency-fn (job :request_body)"

  [handler dependency-builders]
  (fn [job]
    (if-let [missing-dependency-jobs (missing-dependencies (job :request_body) dependency-builders)]
      (apply add-dependencies missing-dependency-jobs)
      (handler job))))