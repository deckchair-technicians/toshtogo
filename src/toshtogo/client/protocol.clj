(ns toshtogo.client.protocol
  (:require [toshtogo.util.core :refer [debug uuid cause-trace assoc-not-nil]]
            [toshtogo.server.persistence.protocol :as server-protocol]))

(defn job-req
  ([body job-type & {:keys [dependencies notes tags]}]
   (-> {:job_type     job-type
        :request_body body}
       (assoc-not-nil :dependencies dependencies
                      :notes notes
                      :tags tags))))

(defn with-dependencies [job-req dependencies]
  (assoc job-req :dependencies dependencies))

(defn fungibility-group [job-req group-id]
  (assoc job-req :fungibility_group_id group-id))

(defn fungibile-under-parent [job-req]
  (assoc job-req :fungibile_under_parent true))

(defn with-name [job-req job-name]
  (assoc job-req :job_name job-name))

(defn with-notes [job-req notes]
  (assoc job-req :notes notes))

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

