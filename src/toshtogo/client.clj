(ns toshtogo.client
  (:require [clj-time.format :as tf]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [uuid cause-trace]]
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
        (when (:cancel (heartbeat! contract)) (future-cancel work-future)))
      @work-future)
    (catch Throwable t
      (error (cause-trace t)))))

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (request-work! [this tags])
  (complete-work! [this commitment-id result])
  (do-work! [this tags f]))

(deftype SenderClient [sender]
  Client
  (put-job! [this job-id job-req]
    (PUT! sender
          (str "/api/jobs/" job-id)
          job-req))

  (get-job [this job-id]
    (update (GET sender (str "/api/jobs/" job-id)) :last_heartbeat #(when % (tf/parse (tf/formatters :date-time) %))))

  (request-work! [this tags]
    (PUT! sender
          "/api/commitments"
          {:commitment_id (uuid)
           :tags tags}))

  (complete-work! [this commitment-id result]
    (PUT! sender
          (str "/api/commitments/" commitment-id)
          result))

  (do-work! [this tags f]
    (future
      (when-let [contract (request-work! this tags)]
        (let [result (with-exception-handling (fn [c] (POST! sender (str "/api/commitments/" (c :commitment_id) "/heartbeat") {})) f contract)]
          (complete-work! this (contract :commitment_id) result)
          {:contract contract
           :result   result})))))

(defn app-sender-client [app]
  (SenderClient. (app-sender app)))

(defn http-sender-client [base-path]
  (SenderClient. (http-sender base-path)))
