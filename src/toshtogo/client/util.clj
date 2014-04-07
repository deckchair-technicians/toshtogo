(ns toshtogo.client.util
  (:import [toshtogo.client.senders SenderException]
           (java.net UnknownHostException InetAddress))
  (:require [trptcolin.versioneer.core :as version]
            [swiss.arrows :refer :all]
            [flatland.useful.map :refer [map-vals]]
            [toshtogo.util.core :refer [retry-until-success exponential-backoff]]))

(defn hostname
  []
  (try
    (.getHostName (InetAddress/getLocalHost))
    (catch UnknownHostException e
      (throw (RuntimeException.
               (str
                 "Can't get hostname. POSSIBLE FIX: http://stackoverflow.com/a/16361018. "
                 "\nException was:"
                 (.getMessage e)))))))

(defn merge-dependency-results
  "Takes a toshtogo job.

  Builds a map of the :result_body of each child job in :dependencies, keyed by the
  :job_type of the dependency.

  Returns the result of merging this job into the parent job's :request_body.

  Useful for making toshtogo agents agnostic to whether dependencies are provided
  directly in the :request_body, or by dependent jobs.

  If there are multiple dependencies of the same type, the last one will win, unless
  the :job_type is specified in merge-multiple, which will cause "
  [job & {:keys [merge-multiple] :or {merge-multiple []}}]
  (let [dependencies               (job :dependencies)
        job-type-result-pairs      (map (fn [dep] [(keyword (:job_type dep)) (:result_body dep)]) dependencies)
        multiple-value-deps        (-<> job-type-result-pairs
                                       (group-by first <>)
                                       (select-keys <> merge-multiple)
                                       (flatland.useful.map/map-vals <> (fn [deps] (map second deps))))
        single-value-deps          (-<> job-type-result-pairs
                                       (mapcat identity <>)
                                       (apply hash-map <>)
                                       (apply dissoc <> merge-multiple))]
    (apply merge (job :request_body)
           (cons multiple-value-deps single-value-deps))))

(defn agent-details
  "Returns a map containing :hostname :system_name :system_version.\n
  \n
  Works out hostname itself.\n
  :system_name will be maven-artifact\n
  \n
  :system_version will be pulled from either the pom.properties file in\n
  META-INF or [maven-artifact].version environment variable set by lein\n
  in the repl."
  [maven-group maven-artifact]
  {:hostname       (hostname)
   :system_name    maven-artifact
   :system_version (version/get-version maven-group maven-artifact)})

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