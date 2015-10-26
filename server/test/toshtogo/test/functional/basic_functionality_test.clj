(ns toshtogo.test.functional.basic-functionality-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]

            [schema.core :as sch]

            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [vice
             [midje :refer [matches]]
             [schemas :refer [when-sorted in-any-order]]]
            [toshtogo.test.functional.test-support :refer :all])
  (:import [clojure.lang ExceptionInfo]))

(background (before :contents @migrated-dev-db))

(def is-nil (sch/pred nil? "nil"))

(def timestamp-tolerance (seconds 5))

(defn close-to
  ([expected]
   (close-to expected timestamp-tolerance))
  ([expected tolerance-period]
   (let [start (minus expected tolerance-period)
         end (plus expected tolerance-period)]
     (sch/pred (fn [x] (within? start end x))
               (str "Within " tolerance-period " of " expected)))))

(with-redefs
  [toshtogo.client.protocol/heartbeat-time 1]
  (fact "Work can be requested"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (request-work! client job-type) => (matches {:job_id       job-id
                                                       :request_body {:a-field "field value"}})))

  (fact "Work requests are idempotent"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! no-retry-client job-id (job-req {:a-field "same content"} job-type))
          (put-job! no-retry-client job-id (job-req {:a-field "same content"} job-type))

          (put-job! no-retry-client job-id (job-req {:a-field "DIFFERENT CONTENT"} job-type))
          => (throws ExceptionInfo "Bad Request")))

  (fact "Work can only be requested once"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (request-work! client job-type) => truthy
          (request-work! client job-type) => nil))

  (fact "Work is returned in order"
        (let [job-id-1 (uuid)
              job-id-2 (uuid)
              job-type (uuid-str)]

          (put-job! client job-id-1 (job-req {} job-type))
          (Thread/sleep 1)
          (put-job! client job-id-2 (job-req {} job-type))

          (request-work! client job-type) => (matches {:job_id job-id-1})))

  (fact "Agents can request work and then complete it"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (let [{:keys [contract result]} @(do-work! client job-type (return-success-with-result {:response-field "all good"}))]
            contract
            => (matches {:job_id job-id :request_body {:a-field "field value"}})
            result
            => (matches {:outcome :success :result {:response-field "all good"}}))

          (get-job client job-id)
          => (matches {:outcome :success :result_body {:response-field "all good"}})))

  (fact "Agents can report errors"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (let [{:keys [contract result]} @(do-work! client job-type
                                                     (constantly (error {:message "something went wrong"})))]
            contract
            => (matches {:job_id job-id :request_body {:a-field "field value"}})
            result
            => (matches {:outcome :error
                         :error {:message "something went wrong"}}))

          (get-job client job-id)
          => (matches {:outcome :error
                       :error {:message "something went wrong"}})))

  (facts "Agent can schedule a job to start later"
         (when (= :app (:type client-config))
           (let [job-id (uuid)
                 job-type (uuid-str)
                 before-due-time (now)
                 due-time (plus before-due-time (minutes 1))]

             (put-job! client job-id (-> (job-req {} job-type)
                                         (with-start-time due-time)))

             (request-work! client job-type) => nil
             (provided (now) => before-due-time)

             @(do-work! client job-type return-success) => truthy
             (provided (now) => due-time))))

  (facts "Agent can request that a job is re-attempted later"
         (when (= :app (:type client-config))
           (let [job-id (uuid)
                 job-type (uuid-str)
                 before-due-time (now)
                 due-time (plus before-due-time (minutes 1))]

             (put-job! client job-id (job-req {} job-type))

             (let [delay (fn [job] (try-later due-time "some error happened"))]
               @(do-work! client job-type delay) => truthy)

             (request-work! client job-type) => nil
             (provided (now) => before-due-time)

             @(do-work! client job-type return-success) => truthy
             (provided (now) => due-time))))

  (facts "Contracts should be prioritised by job creation date, not contract creation date"
         (when (= :app (:type client-config))
           (let [job-id-1 (uuid)
                 job-id-2 (uuid)
                 job-type (uuid-str)
                 before-due-time (now)
                 due-time (plus before-due-time (minutes 1))]

             (put-job! client job-id-1 (job-req {} job-type))
             (Thread/sleep 1)
             (put-job! client job-id-2 (job-req {} job-type))

             (let [delay (fn [job] (try-later due-time "some error happened"))]
               @(do-work! client job-type delay) => truthy)

             (:job_id (request-work! client job-type)) => job-id-1
             (provided (now) => due-time))))

  (fact "do-work! on client reports unhandled exceptions"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (let [func (fn [job] (throw (Exception. "WTF")))
                {:keys [contract result]} @(do-work! client job-type func)]

            contract
            => (matches {:job_id job-id :request_body {:a-field "field value"}})
            result
            => (matches {:outcome :error
                         :error   {:message    #"WTF"
                                   :stacktrace #"WTF"}}))

          (get-job client job-id)
          => (matches {:outcome :error
                       :error   {:message    #"WTF"
                                 :stacktrace #"WTF"}})))

  (facts "Heartbeats get stored, but only if they are more recent than the current heartbeat."
         (let [job-id (uuid)
               job-type (uuid-str)
               start-time-ish (now)]

           (put-job! client job-id (job-req {} job-type))

           @(do-work! client job-type (fn [job] (Thread/sleep 1) (success {:oh "yeah"})))

           (let [{:keys [last_heartbeat]} (get-job client job-id)]
             (after? last_heartbeat start-time-ish) => truthy)))

  (fact "Current job state is serialised between server and client as expected"
        (let [job-id (uuid)
              job-type (uuid-str)
              created-time (now)
              claimed-time (plus created-time (millis 5))
              finished-time (plus claimed-time (millis 5))
              due-time (minus created-time (seconds 5))
              request-body {:a-field "field value"}
              commitment (promise)
              notes "Some description of the job"
              job-name "job name"]

          ; Newly created
          (put-job! client job-id (-> (job-req request-body job-type)
                                      (with-notes notes)
                                      (with-name job-name)))
          (get-job client job-id)
          => (matches {:home_graph_id         sch/Uuid
                       :commitment_agent     is-nil
                       :commitment_id        is-nil
                       :contract_claimed     is-nil
                       :contract_created     (close-to created-time)
                       :contract_due         (close-to due-time)
                       :contract_finished    is-nil
                       :contract_id          sch/Uuid
                       :contract_number      1
                       :dependencies         (sch/eq [])
                       :job_name             job-name
                       :notes                notes
                       :error                is-nil
                       :job_created          (close-to created-time)
                       :job_id               job-id
                       :last_heartbeat       is-nil
                       :outcome              :waiting
                       :request_body         (sch/eq request-body)
                       :requesting_agent     sch/Uuid
                       :result_body          is-nil
                       :job_type             job-type
                       :fungibility_key      is-nil})
          (provided (now) => created-time)

          (deliver commitment (request-work! client job-type))
          => truthy
          (provided (now) => claimed-time)

          (get-job client job-id)
          => (matches {:commitment_agent  sch/Uuid
                       :commitment_id     sch/Uuid
                       :contract_claimed  (close-to claimed-time)
                       :contract_finished is-nil
                       :error             is-nil
                       :last_heartbeat    (close-to claimed-time)
                       :outcome           :running
                       :requesting_agent  sch/Uuid})

          (complete-work! client (@commitment :commitment_id) (success {:some-field "some value"}))
          => truthy
          (provided (now) => finished-time)

          (get-job client job-id)
          => (matches {:contract_finished (close-to finished-time)
                       :contract_number   1
                       :error             is-nil
                       :outcome           :success
                       :result_body       {:some-field "some value"}})))

  (fact "We can request a subset of fields"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {} job-type))

          (-> (get-jobs client {:job_id job-id :fields [:job_id :job_type]})
              :data
              first)
          => (matches {:job_id   job-id
                       :job_type job-type}))))

(fact "Getting a job returns just the immediate dependencies"
      (let [job-id (uuid)
            job-type (uuid-str)]

        ; Newly created
        (put-job! client job-id (job-req {:job "1"} job-type
                                         :dependencies
                                         [(job-req {:job "1.1"} job-type
                                                   :dependencies
                                                   [(job-req {:job "1.1.1"} job-type)
                                                    (job-req {:job "1.1.2"} job-type)])
                                          (job-req {:job "1.2"} job-type)]))

        (get-job client job-id)
        => (matches {:request_body {:job "1"}
                     :dependencies (in-any-order [{:request_body {:job "1.1"}}
                                                  {:request_body {:job "1.2"}}])})))

(fact "Getting a non-existent job returns null"
      (get-job client (uuid)) => nil)
