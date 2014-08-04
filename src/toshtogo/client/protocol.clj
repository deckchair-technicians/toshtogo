(ns toshtogo.client.protocol
  (:import [java.util.concurrent ExecutionException])
  (:require [clojure.set :refer [rename-keys]]
            [clj-time.core :refer [now]]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [ppstr debug uuid cause-trace assoc-not-nil ensure-seq handle-exception]]))

(defn success [response-body]
  {:outcome :success
   :result  response-body})

(defn error [error-text]
  {:outcome :error
   :error   error-text})

(defn cancelled []
  {:outcome :cancelled})

(defn add-dependencies
  "Dependency can either be a (job-req) or the :job_id of an existing job"
  [dependency & dependencies]
  (let [all-deps (concat [dependency] dependencies)
        new-job-dependencies (filter map? all-deps)
        existing-job-dependencies (filter (comp not map?) all-deps)]
    (cond-> {:outcome :more-work}
            (not (empty? new-job-dependencies)) (assoc :dependencies new-job-dependencies)
            (not (empty? existing-job-dependencies)) (assoc :existing_job_dependencies existing-job-dependencies))))

(defn try-later
  ([]
   (try-later (now)))
  ([contract-due]
   {:outcome      :try-later
    :contract_due contract-due})
  ([contract-due error-text]
   (assoc (try-later contract-due)
     :error error-text)))

(defn job-req
  ([body job-type & {:keys [dependencies notes tags]}]
   (-> {:job_type     job-type
        :request_body body}
       (assoc-not-nil :dependencies dependencies
                      :notes notes
                      :tags tags))))

(defn with-dependencies [job-req dependencies]
  (update job-req :dependencies #(concat % (ensure-seq dependencies))))

(defn with-job-id [job-req job-id]
  (assoc job-req :job_id job-id))

(defn with-dependency-on [job-req job-id & job-ids]
  (update job-req :existing_job_dependencies #(concat % [job-id] job-ids)))

(defn fungibility-group [job-req group-id]
  (assoc job-req :fungibility_group_id group-id))

(defn fungible-under-parent [job-req]
  (assoc job-req :fungible_under_parent true))

(defn with-name [job-req job-name]
  (assoc job-req :job_name job-name))

(defn with-notes [job-req notes]
  (assoc job-req :notes notes))

(defn with-start-time [job-req start-time]
  (assoc job-req :contract_due start-time))

(defn with-tags [job-req tags]
  (assoc job-req :tags tags))

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (get-jobs [this query])
  (get-tree [this tree-id])
  (get-job-types [this])
  (pause-job! [this job-id])
  (retry-job! [this job-id])

  (request-work! [this job-type-or-query])
  (heartbeat! [this commitment-id])
  (complete-work! [this commitment-id result]))

(def heartbeat-time 5000)

(defmacro until-done-or-cancelled
  [work-future & body]
  `(doseq [done# (take-while false?
                             (repeatedly (fn []
                                           (or (future-cancelled? ~work-future)
                                               (future-done? ~work-future)))))]
     ~@body)
  )

(defn wrap-heartbeating
  [func heartbeat-fn!]
  (fn [contract]
    (let [work-future (future (func contract))]
      (until-done-or-cancelled work-future
                               (Thread/sleep heartbeat-time)
                               (when (= :cancel (:instruction (heartbeat-fn!)))
                                 (future-cancel work-future)))

      (if (future-cancelled? work-future)
        (cancelled)
        @work-future))))

(defn ->error-response [e]
  (let [ed (if (instance? ExecutionException e)
             (ex-data (.getCause e))
             (ex-data e))]
    (merge
      {:message    (.getMessage e)
       :stacktrace (cause-trace e)}
      (when ed {:ex_data ed}))))

(defn wrap-exception-handling
  [handler]
  (fn [job]
    (try
      (handler job)
      (catch Throwable t
        (error (->error-response t))))))

(defn complete-work-return-result! [client commitment-id result]
  (complete-work! client commitment-id result)
  result)

(def safely-submit-result!
  "Catches exceptions when delivering a result back to the server (for example if the response is
   malformed). Makes heroic efforts to report as much context as possible."
  (handle-exception
    ; Try to send result
    (fn [client commitment-id result]
      (complete-work-return-result! client commitment-id result))

    ; If we can't, send an error response including the exception and the original result we were sending
    (fn [original-exception client commitment-id result]
      (complete-work-return-result!
        client commitment-id
        (error (merge (->error-response original-exception)
                      {:message (str "Problem sending result " (.getMessage original-exception))
                       :original_result  result}))))

    ; If that doesn't work, try stringifying the result in case it's a json serialisation problem
    (fn [original-exception client commitment-id result]
      (complete-work-return-result!
        client commitment-id
        (error (-> (->error-response original-exception)
                   (assoc :message (str "Problem sending result. Result cannot be json encoded. " (.getMessage original-exception)))
                   (assoc :original_result_str (ppstr result))))))

    ; Or... is the ex-data the problem?
    (fn [original-exception client commitment-id result]
      (complete-work-return-result!
        client commitment-id
        (error (-> (->error-response original-exception)
                   (assoc :message (str "Problem sending result. Either result or ex-data cannot be json encoded. " (.getMessage original-exception)))
                   (assoc :original_result_str (ppstr result))
                   (update :ex_data ppstr)
                   (rename-keys {:ex_data :ex_data_str})))))

    ; Give up....
    (fn [original-exception client commitment-id result]
      (complete-work-return-result!
        client commitment-id
        (error {:message    (str "Catastrophic problems sending result.  "
                                 (.getMessage original-exception))
                :stacktrace (cause-trace original-exception)})))))

(defn do-work! [client job-type-or-query handler-fn]
  (future
    (when-let [contract (request-work! client job-type-or-query)]
      (let [commitment-id (contract :commitment_id)
            heartbeat-fn! #(heartbeat! client commitment-id)
            wrapped-fn (-> handler-fn
                           (wrap-heartbeating heartbeat-fn!)
                           (wrap-exception-handling))
            ; This may take some time to return
            result (wrapped-fn contract)
            result (safely-submit-result! client commitment-id result)]
        {:contract contract
         :result   result}))))
