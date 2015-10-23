(ns toshtogo.server.heartbeat.core
  (:import [java.util.concurrent ScheduledExecutorService])
  (:require [cheshire.core :as json]

            [clj-time
             [format :as tf]
             [core :as t]
             [coerce :as tc]]

            [toshtogo.client.protocol :refer [get-jobs complete-work! retry-job! get-job]])
  (:import [java.util.concurrent Executors TimeUnit ScheduledExecutorService]))

(defn parse-datetime
  [s]
  (when s
    (tf/parse (tf/formatters :date-time-parser) s)))

(def pool (Executors/newScheduledThreadPool 1))

(defn seconds-to-millis [seconds]
  (. TimeUnit/SECONDS (toMillis seconds)))

(defn check-heartbeat
  [time-now max-ttl {:keys [commitment_id last_heartbeat contract_claimed job_id]}]
  (println "check-heartbeat" commitment_id last_heartbeat contract_claimed)
  (if last_heartbeat
    (let [time-since-last-heartbeat (- (tc/to-long time-now) (tc/to-long last_heartbeat))]
      (when (< (seconds-to-millis max-ttl) time-since-last-heartbeat)
        {:commitment_id commitment_id :job_id job_id}))

    (let [time-since-claimed (- (tc/to-long time-now) (tc/to-long contract_claimed))]
      (when (< (seconds-to-millis max-ttl) time-since-claimed)
        {:commitment_id commitment_id :job_id job_id}))))

(defn get-running-toshtogo-jobs
  [toshtogo-client]
  (let [jobs (get-jobs toshtogo-client {:outcome :running})]
    (:data jobs)))

(defn cancel-commitment! [toshtogo-client commitment-id]
  (println "cancel-commitment" commitment-id)
  (complete-work! toshtogo-client commitment-id {:outcome :cancelled}))


(defn cancel-retry-commitment [toshtogo-client commitment-id job-id]
  (println "cancel-retry-commitment" commitment-id job-id)
  (cancel-commitment! toshtogo-client commitment-id)
  (retry-job! toshtogo-client job-id))

(defn cancel-commitments! [toshtogo-client jobs]
  (println "cancelling:" (count jobs))
  (doall (map (fn [job] (cancel-retry-commitment toshtogo-client (:commitment_id job) (:job_id job))) jobs)))

(defn check-heartbeats!
  [toshtogo-client max-ttl]
  (try
    (do
      (println "check-heartbeats!")
      (let [time-now (t/now)
            jobs (get-running-toshtogo-jobs toshtogo-client)
            running-jobs (->> jobs
                              (map #(select-keys % [:contract_claimed :commitment_id :last_heartbeat :job_id])))]

        (->> running-jobs
             (map (partial check-heartbeat time-now max-ttl))
             (remove nil?)
             (#(when-not (empty? %) (cancel-commitments! toshtogo-client %))))))
    (catch Exception e (do
                         (.printStackTrace e)
                         (throw e)))))

(defn schedule [f interval-seconds]
  (.scheduleWithFixedDelay pool f interval-seconds interval-seconds TimeUnit/SECONDS))

(defn start-monitoring! [toshtogo-client interval-seconds max-ttl]
  (println "start-monitoring!")
  (schedule #(check-heartbeats! toshtogo-client max-ttl) interval-seconds))
