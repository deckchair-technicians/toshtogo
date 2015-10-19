(ns toshtogo.client.middleware
  (:require [clojure.pprint :refer [pprint]]
            [clj-time.core :as t]
            [toshtogo.util.json :as json]
            [toshtogo.client.util :refer [merge-dependency-results]]
            [toshtogo.client.protocol :refer :all]))

(defn wrap-merge-dependency-results
  "Merges the :result_body from all :dependencies into the \n
  :request_body of the job and assigns it to :combined_request.\n
  \n
  Useful for making toshtogo agents agnostic to whether dependencies are provided\n
  directly in the :request_body, or by dependent jobs.\n

  :merge-multiple DEPRECATED. Use
  :job-type->merger {:some-job-type concat-results-of-multiple-jobs}

  :job-type->merger
  A map from :job_type to a (fn [jobs]) which normal return the :result_body of the dependency.

  Dependencies are grouped by job type and handed to the appropriate merge function.

  If no function exists for a job type, the default is to return the result of the last or only job of this type."
  [handler & {:keys [merge-multiple job-type->merger]
              :or {merge-multiple []
                   job-type->merger {}}}]
  (fn [job]
    (handler (assoc job :request_body (merge-dependency-results job
                                                                :merge-multiple merge-multiple
                                                                :job-type->merger job-type->merger)))))

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

  [handler dependency-builders & {:keys [request-mapper]
                                  :or   {request-mapper :request_body}}]
  (fn [job-or-request]
    (if-let [missing-dependency-jobs (missing-dependencies (request-mapper job-or-request) dependency-builders)]
      (apply add-dependencies missing-dependency-jobs)
      (handler job-or-request))))

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

(defn wrap-assoc
  "assocs keys and values onto the job/request and passes it down to handler"
  [handler key val & keyvals]
  (fn [job]
    (handler (apply assoc job key val keyvals))))

(defn wrap-extract-request [handler]
  (fn [job]
    (handler (:request_body job))))

(defn wrap-map-request [handler f]
  (fn [request]
    (handler (f request))))

(defn wrap-map-result [handler f]
  (fn [job]
    (let [resp (handler job)]
      (if (= :success (:outcome resp))
        (update resp :result f)
        resp))))

(defn wrap-mapping [handler request-fn result-fn]
  (-> handler
      (wrap-map-request request-fn)
      (wrap-map-result result-fn)))

; Try later

(defn retry-response [{:as request :keys [retry]}  error-response error-handler]
  (if (t/after? (t/now) (:until retry))
    (error-handler request error-response)
    (try-later (t/plus (t/now) (t/minutes (:every-minutes retry))))))

(defn wrap-try-later
  "error-handler is a function which takes the request body and the error response
   and returns a potentially transformed  response defaults to a function which just
   returns the original error"
  [handler & {:as opts :keys [error-handler] :or {error-handler (fn [req resp] resp)}}]
  (fn [request]
    (let [response (handler request)]
      (if (and (= :error (:outcome response))
               (:retry request))
        (retry-response request response error-handler)
        response))))
