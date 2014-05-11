(ns toshtogo.client.protocol
  (:require [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [debug uuid cause-trace assoc-not-nil ensure-seq]]
            [toshtogo.server.persistence.protocol :as server-protocol]))

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

(def success server-protocol/success)
(def error server-protocol/error)
(def cancelled server-protocol/cancelled)
(def add-dependencies server-protocol/add-dependencies)
(def try-later server-protocol/try-later)

(defprotocol Client
  (put-job! [this job-id job-req])
  (get-job [this job-id])
  (get-jobs [this query])
  (get-job-types [this])
  (pause-job! [this job-id])
  (retry-job! [this job-id])

  (request-work! [this job-type])
  (heartbeat! [this commitment-id])
  (complete-work! [this commitment-id result]))

(def heartbeat-time 1000)

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
                               (when (= :cancel (:instruction (heartbeat-fn! contract)))
                                 (future-cancel work-future)))

      (if (future-cancelled? work-future)
        (cancelled)
        @work-future))))

(defn wrap-exception-handling
  [func]
  (fn [contract]
    (try
      (func contract)
      (catch Throwable t
        (error (cause-trace t))))))

(defn do-work! [client job-type func]
  (future
    (when-let [contract (request-work! client job-type)]
      (let [heartbeat-fn! (fn [contract] (heartbeat! client (contract :commitment_id)))
            wrapped-fn (-> func
                           (wrap-exception-handling)
                           (wrap-heartbeating heartbeat-fn!))
            result (wrapped-fn contract)]
        (when (not (= :cancelled (result :outcome)))
          (complete-work! client (contract :commitment_id) result))
        {:contract contract
         :result   result}))))

