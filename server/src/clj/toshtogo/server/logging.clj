(ns toshtogo.server.logging
  (:import (java.util Map))
  (:require [clojure.stacktrace :refer [print-cause-trace]]
            [schema.core :as sch]
            [clojure.string :as s]
            [clojure.pprint :refer [pprint]]

            [toshtogo.schemas :refer [Agent]]

            [toshtogo.util.schema :as toshtogo-schema]
            [toshtogo.util.core :refer [exception-as-map with-sys-out]]
            [toshtogo.server.validation :refer [JobRecord JobResult validated]]))

; ----------------------------------------------
; Event schemas and construcors
; ----------------------------------------------

(defn event-type= [event-type]
  (fn [event]
    (= (:event_type event) event-type)))

(def CommitmentDetails
  {:commitment_id sch/Uuid
   :job_id        sch/Uuid
   :job_type      sch/Keyword
   :job_name      (sch/maybe sch/Str)
   :request_body  Map
   :agent         Agent})

(def LoggingEvent
  ;TODO: Better as a macro?
  (sch/conditional
    (event-type= :server_error)
    {:event_type (sch/eq :server_error)
     :event_data {:stacktrace         sch/Str
                  :message            (sch/maybe sch/Str)
                  :class              sch/Str
                  :ex_data            sch/Any
                  :http_method        sch/Str
                  :url                sch/Str
                  :rolled_back_events (sch/maybe [(toshtogo-schema/recursive #'LoggingEvent)])}}

    (event-type= :new_job)
    {:event_type (sch/eq :new_job)
     :event_data JobRecord}

    (event-type= :commitment_started)
    {:event_type (sch/eq :commitment_started)
     :event_data CommitmentDetails}

    (event-type= :commitment_result)
    {:event_type (sch/eq :commitment_result)
     :event_data (assoc CommitmentDetails
                   :result JobResult)}

    (event-type= :request)
    {:event_type (sch/eq :request)
     :event_data sch/Any}))

(defn new-job-event
  [job]
  {:event_type :new_job
   :event_data job})

(defn commitment-details [contract agent-details]
  (assoc (select-keys contract [:job_id :commitment_id :job_type :job_name :request_body])
    :agent agent-details))

(defn commitment-started-event
  [commitment-id contract agent-details]
  {:event_type :commitment_started
   :event_data (commitment-details (assoc contract :commitment_id commitment-id) agent-details)})

(defn commitment-result-event
  [contract agent-details result]
  {:event_type :commitment_result
   :event_data (assoc (commitment-details contract agent-details)
                 :result result)})

(defn error-event
  [exception rolled-back-events request]
  {:event_type :server_error
   :event_data (assoc (exception-as-map exception)
                 :rolled_back_events rolled-back-events
                 :url (:uri request)
                 :http_method (s/upper-case (name (:request-method request))))})

; ----------------------------------------------
; Loggers
; ----------------------------------------------

(defprotocol Logger
  (log [this event] "Event is a toshtogo.server.logging.LoggingEvent"))

(defn safe-log
  "Tries to log events, catches exceptions and prints stack trace."
  [logger events]
  (doseq [event events]
    (try
      (log logger event)
      (catch Exception logging-exception
        (println)
        (println "Exception during logging! Printing to stdout and continuing")
        (when (ex-data logging-exception)
          (println "ex-data")
          (clojure.pprint/pprint (ex-data logging-exception))
          (println "Stack-trace:"))
        (print-cause-trace logging-exception)
        (println)
        ))))

(extend-protocol Logger
  nil
  (log [_this _event]
    nil))

(defrecord SysLogger []
  Logger
  (log [_this event]
    (with-sys-out
      (println)
      (println (str "Event [" (name (:event_type event)) "]"))
      (println "---------------------------")
      (pprint event))))

(defrecord ValidatingLogger [decorated]
  Logger
  (log [this event]
    (log (:decorated this) (validated event LoggingEvent))))

(defrecord DeferredLogger [log-seq-atom]
  Logger
  (log [this event]
    (swap! (:log-seq-atom this) #(conj % event))))
