(ns toshtogo.client
  (:require [clj-time.format :as tf]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [uuid cause-trace debug]]
            [toshtogo.api :refer [success error]]
            [toshtogo.client.senders :refer :all]
            [toshtogo.client.http :refer :all]
            [clojure.stacktrace :as stacktrace]))

(defn job-req
  ([body tags]
     {:tags tags
      :request_body body})
  ([body tags dependencies]
     (assoc (job-req body tags) :dependencies dependencies)))

(def heartbeat-time 1000)

(defn with-exception-handling
  [heartbeat! f contract]
  (try
    (let [work-future (future (f contract))]
      (doseq [done? (take-while false? (repeatedly (fn [] (or (future-cancelled? work-future)
                                                             (future-done? work-future)))))]
        (Thread/sleep heartbeat-time)
        (when (= "cancel" (:instruction (heartbeat! contract))) (future-cancel work-future)))
      @work-future)
    (catch Throwable t
      (error (cause-trace t)))))

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (pause-job! [this job-id])
  (request-work! [this tags])
  (heartbeat! [this commitment-id])
  (complete-work! [this commitment-id result])
  (do-work! [this tags f]))

(defn sender-client [sender]
  (reify
    Client
    (put-job! [this job-id job-req]
      (PUT! sender
            (str "/api/jobs/" job-id)
            job-req))

    (get-job [this job-id]
      (update (GET sender (str "/api/jobs/" job-id)) :last_heartbeat #(when % (tf/parse (tf/formatters :date-time) %))))

    (pause-job! [this job-id]
      (POST! sender
             (str "/api/jobs/" job-id "/pause")
             nil))

    (request-work! [this tags]
      (PUT! sender
            "/api/commitments"
            {:commitment_id (uuid)
             :tags tags}))

    (complete-work! [this commitment-id result]
      (PUT! sender
            (str "/api/commitments/" commitment-id)
            result))

    (heartbeat! [this commitment-id]
      (POST! sender (str "/api/commitments/" commitment-id "/heartbeat") {}))

    (do-work! [this tags f]
      (future
        (when-let [contract (request-work! this tags)]
          (let [result (with-exception-handling (fn [c] (heartbeat! this (c :commitment_id))) f contract)]
            (complete-work! this (contract :commitment_id) result)
            {:contract contract
             :result   result}))))))

(defn app-client [app]
  (sender-client (app-sender app)))

(defn http-client [base-path]
  (sender-client (http-sender base-path)))
