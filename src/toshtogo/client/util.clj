(ns toshtogo.client.util
  (:import [toshtogo.client.senders SenderException]
           [toshtogo.client BadRequestException]
           [java.net UnknownHostException InetAddress])
  (:require [trptcolin.versioneer.core :as version]
            [swiss.arrows :refer :all]
            [flatland.useful.map :refer [map-vals]]))

(def hostname
  (delay
    (try
      (.getHostName (InetAddress/getLocalHost))
      (catch UnknownHostException e
        (throw (RuntimeException.
                 (str
                   "Can't get hostname. POSSIBLE FIX: http://stackoverflow.com/a/16361018. "
                   "\nException was:"
                   (.getMessage e))))))))

(defn dependency-merger [left right]
  (if (sequential? right)
    (if (not (sequential? left))
      (throw (IllegalArgumentException. (str "Cannot merge non-sequential left with sequential right. Left:\n" left "\n\nRight:\n" right)))
      (concat left right))
    right))

(defn merge-dependency-results
  "Takes a toshtogo job.

  Builds a map of the :result_body of each child job in :dependencies, keyed by the
  :job_type of the dependency.

  Returns the result of merging this job into the parent job's :request_body.

  Useful for making toshtogo agents agnostic to whether dependencies are provided
  directly in the :request_body, or by dependent jobs.

  If there are multiple dependencies of the same type, the last one will win, unless
  the :job_type is specified in merge-multiple, which will cause all dependencies of that
  type to be added as a sequence under their :job_type"
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
    (apply merge (merge-with dependency-merger (job :request_body) multiple-value-deps) single-value-deps)))

(defn agent-details*
  "Returns a map containing :hostname :system_name :system_version.\n
  \n
  Works out hostname itself.\n
  :system_name will be maven-artifact\n
  \n
  :system_version will be pulled from either the pom.properties file in\n
  META-INF or [maven-artifact].version environment variable set by lein\n
  in the repl."
  [maven-group maven-artifact]
  {:hostname       @hostname
   :system_name    maven-artifact
   :system_version (version/get-version maven-group maven-artifact)})

(def agent-details (memoize agent-details*))

(defmacro throw-500
  [& body]
  `(let [result# (do ~@body)]
     (if (and (:status result#) (< 499 (:status result#) 600))
       (throw (SenderException. (str result#)))
       result#)))

(defmacro throw-400
  [& body]
  `(let [result# (do ~@body)]
     (if (= 400 (:status result#))
       (throw (BadRequestException. (str result#)))
       result#)))

(defmacro nil-on-404
  [& body]
  `(let [result# (do ~@body)]
     (if (= 404 (:status result#))
       nil
       result#)))

(defn url-str
  "Basic base-url to query joining. Returns a string"
  [base-url & path-segments]
  (str
   (reduce
    (fn [url segment]
      (let [stripped-url (->> url
                              (clojure.string/trim)
                              (reverse)
                              (drop-while #(= \/ %))
                              (reverse)
                              (apply str))

            stripped-segment (->> segment
                                  (clojure.string/trim)
                                  (drop-while #(= \/ %))
                                  (apply str))]
        (str stripped-url "/" stripped-segment)))
    base-url
    path-segments)))
