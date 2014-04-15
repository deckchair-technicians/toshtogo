(ns toshtogo.client.middleware
  (:require [clojure.pprint :refer [pprint]]
            [toshtogo.client.util :refer [merge-dependency-results]]
            [toshtogo.client.protocol :refer :all]))

(defn wrap-merge-dependency-results
  "Merges the :result_body from all :dependencies into the \n
  :request_body of the job and assigns it to :combined_request.\n
  \n
  Useful for making toshtogo agents agnostic to whether dependencies are provided\n
  directly in the :request_body, or by dependent jobs.\n

  :merge-multiple is an optional keyword argument which takes a sequence\n
  of job types for which a sequence of all dependencies with that job type will be\n
  returned (as opposed to just the last dependency for each job type, which is the\n
  default behaviour)"
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

(defn wrap-print-job [handler]
  (fn [job]
    (println "REQUEST")
    (pprint job)
    (println)

    (let [response (handler job)]

      (println "RESPONSE")
      (pprint response)
      (println)
      response)))

(defn wrap-extract-request [handler]
  (fn [job]
    (handler (:request_body job))))
