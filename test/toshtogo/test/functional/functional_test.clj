(ns toshtogo.test.functional.functional-test
  (:import (java.util UUID)
           (toshtogo.client.senders SenderException))
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]))

(background (before :contents @migrated-dev-db))

(with-redefs
  [toshtogo.client.protocol/heartbeat-time 1]
  (fact "Work can be requested"
        (let [job-id   (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (request-work! client job-type) => (contains {:job_id       job-id
                                                        :request_body {:a-field "field value"}})))

  (fact "Work requests are idempotent"
        (let [job-id   (uuid)
              job-type (uuid-str)]

          (put-job! no-retry-client job-id (job-req {:a-field "same content"} job-type))
          (put-job! no-retry-client job-id (job-req {:a-field "same content"} job-type))

          (put-job! no-retry-client job-id (job-req {:a-field "DIFFERENT CONTENT"} job-type))
          => (throws SenderException)))

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

          (request-work! client job-type) => (contains {:job_id job-id-1})))

  (fact "Agents can request work and then complete it"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (let [{:keys [contract result]} @(do-work! client job-type (return-success-with-result {:response-field "all good"}))]
            contract
            => (contains {:job_id job-id :request_body {:a-field "field value"}})
            result
            => (contains {:outcome :success :result {:response-field "all good"}}))

          (get-job client job-id)
          => (contains {:outcome :success :result_body {:response-field "all good"}})))

  (fact "Agents can report errors"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (let [{:keys [contract result]} @(do-work! client job-type return-error)]
            contract
            => (contains {:job_id job-id :request_body {:a-field "field value"}})
            result
            => (contains {:outcome :error :error "something went wrong"}))

          (get-job client job-id)
          => (contains {:outcome :error :error "something went wrong"})))

  (facts "Agent can request that a job is re-attempted"
         (when (= :app (:type client-config))
           (let [job-id (uuid)
                 job-type (uuid-str)
                 before-due-time (now)
                 due-time (plus before-due-time (minutes 1))]

             (put-job! client job-id (job-req [] job-type))

             (let [delay (fn [job] (try-later due-time "some error happened"))]
               @(do-work! client job-type delay) => truthy)

             (request-work! client job-type) => nil
             (provided (now) => before-due-time)

             @(do-work! client job-type return-success) => truthy
             (provided (now) => due-time))))

  (fact "do-work! on client reports unhandled exceptions"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (let [func (fn [job] (throw (Exception. "WTF")))
                {:keys [contract result]} @(do-work! client job-type func)]

            contract
            => (contains {:job_id job-id :request_body {:a-field "field value"}})
            result
            => (contains {:outcome :error :error (contains "WTF")}))

          (get-job client job-id)
          => (contains {:outcome :error :error (contains "WTF")})))

  (facts "Heartbeats get stored, but only if they are more recent than the current heartbeat."
         (let [job-id (uuid)
               job-type (uuid-str)
               start-time-ish (now)]

           (put-job! client job-id (job-req [] job-type))

           @(do-work! client job-type (fn [job] (Thread/sleep 1) (success "Oh yeah")))

           (let [{:keys [last_heartbeat]} (get-job client job-id)]
             (after? last_heartbeat start-time-ish) => truthy)))

(fact "Current job state is serialised between server and client as expected"
      (let [job-id (uuid)
            commitment-id (atom "not set")
            job-type (uuid-str)
            tags (set [(keyword (uuid-str)) (keyword (uuid-str))])
            created-time (now)
            claimed-time (plus created-time (millis 5))
            finished-time (plus claimed-time (millis 5))
            due-time (minus created-time (seconds 5))
            request-body {:a-field "field value"}
            commitment  (promise)
            notes "Some description of the job"
            job-name "job name"]

        ; Newly created
        (put-job! client job-id (-> (job-req request-body job-type)
                                    (with-tags tags)
                                    (with-notes notes)
                                    (with-name job-name)))
        (get-job client job-id)
        => (just {:commitment_agent    nil
                  :commitment_id       nil
                  :contract_claimed    nil
                  :contract_created    (close-to created-time)
                  :contract_due        (close-to due-time)
                  :contract_finished   nil
                  :contract_id         (isinstance UUID)
                  :contract_number     1
                  :dependencies        []
                  :job_name            job-name
                  :notes               notes
                  :error               nil
                  :job_created         (close-to created-time)
                  :job_id              job-id
                  :last_heartbeat      nil
                  :outcome             :waiting
                  :request_body        request-body
                  :requesting_agent    (isinstance UUID)
                  :result_body         nil
                  :job_type            job-type
                  :tags                (just tags :in-any-order)
                  :fungibility_group_id job-id})
        (provided (now) => created-time)

        (deliver commitment (request-work! client job-type))
        => truthy
        (provided (now) => claimed-time)

        (get-job client job-id)
        => (contains {:commitment_agent  (isinstance UUID)
                      :commitment_id     (isinstance UUID)
                      :contract_claimed  (close-to claimed-time)
                      :contract_finished nil
                      :error             nil
                      :last_heartbeat    (close-to claimed-time)
                      :outcome           :running
                      :requesting_agent  (isinstance UUID)})

        (complete-work! client (@commitment :commitment_id) (success {:some-field "some value"}))
        => truthy
        (provided (now) => finished-time)

        (get-job client job-id)
        => (contains {:contract_finished (close-to finished-time)
                      :contract_number   1
                      :error             nil
                      :outcome           :success
                      :result_body       {:some-field "some value"}}))))

(fact "Current job state is serialised between server and client as expected"
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
        => (contains {:request_body {:job "1"}
                      :dependencies (contains [
                                                (contains {:request_body {:job "1.1"}
                                                           :dependencies (contains [(contains {:request_body {:job "1.1.1"}})
                                                                                    (contains {:request_body {:job "1.1.2"}})]
                                                                                   :in-any-order)})
                                                (contains {:request_body {:job "1.2"}})
                                                ]
                                              :in-any-order)})))
(fact "Getting a non-existent job returns null"
      (get-job client (uuid)) => nil)
