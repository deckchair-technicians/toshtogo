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

(defn pick-highest-sequence-number
  "Takes the job with the highest :sequence_number in :request_body.

  Returns the :result_body from that job, with :sequence_number merged back into it, so that the dependency doesn't need
  to know anything about sequence numbers (except to have a loose schema so the extra field in request doesn't cause an
  error)"
  [jobs]
  (let [latest-job (->> jobs
                         (reduce (fn [job-a job-b]
                                   (if (> (get-in job-a [:request_body :sequence_number])
                                          (get-in job-b [:request_body :sequence_number]))
                                     job-a
                                     job-b))))
        sequence-number (get-in latest-job [:request_body :sequence_number])]
    (-> latest-job
        :result_body
        (assoc :sequence_number sequence-number))))

(defn concat-results-of-multiple-jobs [jobs]
  (map :result_body jobs))

(def take-last-or-only-result-body
  (comp :result_body last))

(defn merge-dependency-results
  [job & {:keys [merge-multiple job-type->merger]
          :or {merge-multiple []
               job-type->merger {}}}]
  (let [job-type->merger (merge (zipmap merge-multiple (repeat concat-results-of-multiple-jobs))
                                job-type->merger)
        job-type->merged-dependencies (->> job
                                           :dependencies
                                           (group-by :job_type)
                                           (map (fn [[job-type jobs]]
                                                  (let [merger (job-type->merger job-type take-last-or-only-result-body)]
                                                    [job-type (merger jobs)])))
                                           (into {}))]
    (merge-with dependency-merger (job :request_body) job-type->merged-dependencies)))

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
