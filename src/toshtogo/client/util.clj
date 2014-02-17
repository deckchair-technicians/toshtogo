(ns toshtogo.client.util
  (:import [toshtogo.client.senders SenderException]
           (java.net UnknownHostException InetAddress))
  (:require [toshtogo.util.core :refer [retry-until-success exponential-backoff]]))

(defn- hostname
  []
  (try
    (.getHostName (InetAddress/getLocalHost))
    (catch UnknownHostException e
      (throw (RuntimeException.
               (str
                 "Can't get hostname. POSSIBLE FIX: http://stackoverflow.com/a/16361018. "
                 "\nException was:"
                 (.getMessage e)))))))

(defn get-agent-details [system version]
  {:hostname       (hostname)
   :system_name    system
   :system_version version})
(defn request-with-dependency-results
  "Takes a toshtogo job.

  Builds a map of the :result_body of each child job in :dependencies, keyed by the
  :job_type of the dependency.

  Returns the result of merging this job into the parent job's :request_body.

  Useful for making toshtogo agents agnostic to whether dependencies are provided
  directly in the :request_body, or by dependent jobs.

  If there are multiple dependencies of the same type, the last one will win, so this
  is probably not the function you want."
  [job]
  (apply merge (job :request_body)
         (map (fn [dep] {(keyword (:job_type dep)) (:result_body dep)})
              (job :dependencies))))


(defmacro throw-500
  [& body]
  `(let [result# (do ~@body)]
     (if (and (:status result#) (< 499 (:status result#) 600))
       (throw (SenderException. (str result#)))
       result#)))

(defmacro nil-on-404
  [& body]
  `(let [result# (do ~@body)]
     (if (= 404 (:status result#))
       nil
       result#)))

(defmacro until-successful-response
  "Calls body repeatedly until it gets a non-500 response"
  [opts & body]
  (assert (not (empty? body)))
  `(retry-until-success ~opts (throw-500 ~@body)))