(ns toshtogo.test.client.clients.sender-client-test
  (:import (java.util UUID))
  (:require [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [midje.sweet :refer :all]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]
            [toshtogo.server.core :refer [dev-app]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.client.core :as ttc]
            [toshtogo.util.core :refer [uuid uuid-str debug cause-trace]]))

(def in-process {:type :app :app (dev-app :debug false)})
(def localhost {:type :http :base-path "http://localhost:3000"})

(def client-config in-process)
(def client (ttc/client client-config
                        :error-fn (fn [e] (println (cause-trace e)))
                        :debug false
                        :timeout 1000
                        :system "client-test"
                        :version "0.0"))

(def timestamp-tolerance (case (client-config :type)
                           :app (millis 1)
                           :http (seconds 5)))

(defn return-success [job] (success {:result 1}))

(with-redefs
  [toshtogo.client.protocol/heartbeat-time 1]
  (fact "Work can be requested"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (request-work! client job-type) => (contains {:job_id       job-id
                                                        :request_body {:a-field "field value"}})))

  (fact "Work can only be requested once"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (request-work! client job-type)
          (request-work! client job-type) => nil))

  (fact "Agents can request work and then complete it"
        (let [job-id (uuid)
              job-type (uuid-str)]

          (put-job! client job-id (job-req {:a-field "field value"} job-type))

          (let [func (fn [job] (success {:response-field "all good"}))
                {:keys [contract result]} @(do-work! client job-type func)]
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

          (let [func (fn [job] (error "something went wrong"))
                {:keys [contract result]} @(do-work! client job-type func)]
            contract
            => (contains {:job_id job-id :request_body {:a-field "field value"}})
            result
            => (contains {:outcome :error :error "something went wrong"}))

          (get-job client job-id)
          => (contains {:outcome :error :error "something went wrong"})))

  (fact "Client can report unhandled exceptions"
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

  (facts "Jobs can have dependencies"
         (let [job-id (uuid)
               parent-job-type (uuid-str)
               child-job-type (uuid-str)]

           (put-job!
             client
             job-id (job-req
                      {:a "field value"} parent-job-type
                      :dependencies [(job-req {:b "child one"} child-job-type)
                                     (job-req {:b "child two"} child-job-type)]))

           (fact "No contract is created for parent job"
                 (request-work! client parent-job-type) => nil)

           (let [func (fn [job] (success (job :request_body)))]
             (fact "Dependencies are executed in order"
                   (:contract @(do-work! client child-job-type func))
                   => (contains {:request_body {:b "child one"}}))

             (fact "Parent job is not ready until all dependencies complete"
                   (request-work! client parent-job-type) => nil
                   (get-job client job-id) => (contains {:dependencies_succeeded 1}))

             @(do-work! client child-job-type func)
             (get-job client job-id) => (contains {:dependencies_succeeded 2})

             (fact (str "Parent job is released when dependencies are complete, "
                        "with dependency responses merged into its request")
                   (let [contract (request-work! client parent-job-type)]
                     contract
                     => (contains {:request_body {:a "field value"}})

                     (contract :dependencies)
                     => (contains [(contains {:result_body {:b "child one"}})
                                   (contains {:result_body {:b "child two"}})]
                                  :in-any-order))))))

  (facts "Requesting more work"
         (let [job-id (uuid)
               parent-job-type (uuid-str)
               child-job-type (uuid-str)]

           (put-job! client job-id (job-req {:parent-job "parent job"} parent-job-type))

           (let [add-deps (fn [job]
                            (add-dependencies
                              (job-req {:first-dep "first dep"} child-job-type)
                              (job-req {:second-dep "second dep"} child-job-type)))
                 complete-child (fn [job] (success (job :request_body)))]

             @(do-work! client parent-job-type add-deps) => truthy

             (fact "Parent job is not ready until new dependencies complete"
                   (request-work! client parent-job-type) => nil)

             @(do-work! client child-job-type complete-child) => truthy
             @(do-work! client child-job-type complete-child) => truthy

             (fact (str "Parent job is released when dependencies are complete, "
                        "with dependency responses merged into its request")
                   (let [contract (request-work! client parent-job-type)]
                     contract
                     => (contains {:request_body {:parent-job "parent job"}})

                     (contract :dependencies)
                     => (contains [(contains {:result_body {:first-dep "first dep"}})
                                   (contains {:result_body {:second-dep "second dep"}})]
                                  :in-any-order))))))


  (facts "Requesting more work"
         (let [job-id (uuid)
               parent-job-type (uuid-str)
               child-job-type (uuid-str)]

           (put-job! client job-id (job-req {:parent-job "parent job"} parent-job-type))

           (let [add-deps (fn [job]
                            (add-dependencies
                              (job-req {:first-dep "first dep"} child-job-type)
                              (job-req {:second-dep "second dep"} child-job-type)))
                 complete-child (fn [job] (success (job :request_body)))]

             @(do-work! client parent-job-type add-deps) => truthy

             (fact "Parent job is not ready until new dependencies complete"
                   (request-work! client parent-job-type) => nil)

             @(do-work! client child-job-type complete-child) => truthy
             @(do-work! client child-job-type complete-child) => truthy

             (fact (str "Parent job is released when dependencies are complete, "
                        "with dependency responses merged into its request")
                   (let [contract (request-work! client [parent-job-type])]
                     contract
                     => (contains {:request_body {:parent-job "parent job"}})

                     (contract :dependencies)
                     => (contains [(contains {:result_body {:first-dep "first dep"}})
                                   (contains {:result_body {:second-dep "second dep"}})]
                                  :in-any-order))))))

  (facts "Try again later"
         (when (= :app (:type client-config))
           (let [job-id (uuid)
                 job-type (uuid-str)
                 before-due-time (now)
                 due-time (plus before-due-time (minutes 1))]

             (put-job! client job-id (job-req [] job-type))

             (let [delay (fn [job] (try-later due-time "some error happened"))]
               @(do-work! client job-type delay)) => truthy

             (request-work! client job-type) => nil
             (provided (now) => before-due-time)

             @(do-work! client job-type return-success) => truthy
             (provided (now) => due-time))))

  (facts "Heartbeats get stored, but only if they are more recent than the current heartbeat."
         (let [job-id (uuid)
               job-type (uuid-str)
               start-time-ish (now)]

           (put-job! client job-id (job-req [] job-type))

           @(do-work! client job-type (fn [job] (Thread/sleep 1) (success "Oh yeah")))

           (let [{:keys [last_heartbeat]} (get-job client job-id)]
             (after? last_heartbeat start-time-ish) => truthy))))

(with-redefs
  [toshtogo.client.protocol/heartbeat-time 1]
  (facts "Agents receive a cancellation signal in the heartbeat response when jobs are paused"
         (let [job-id (uuid)
               job-type (uuid-str)
               start-time-ish (now)
               commitment-id (promise)]

           (put-job! client job-id (job-req {} job-type))

           (let [commitment (do-work! client job-type (fn [job]
                                                         (deliver commitment-id (job :commitment_id))
                                                         (Thread/sleep 10000)
                                                         (error "Should never return")))]
             (future-done? commitment) => falsey

             (heartbeat! client @commitment-id)
             => (contains {:instruction :continue})

             (get-job client job-id)
             => (contains {:outcome :running})

             (pause-job! client job-id)

             (get-job client job-id)
             => (contains {:outcome :cancelled})

             (deref commitment 5000 nil)
             => (contains {:result {:outcome :cancelled}})

             (Thread/sleep 100)
             (future-done? commitment) => truthy

             (future-cancel commitment))))

  (fact "Paused jobs can be retried"
         (let [job-id (uuid)
               job-type (uuid-str)]

           (put-job! client job-id (job-req {} job-type))

           (pause-job! client job-id)

           (get-job client job-id)
           => (contains {:outcome :cancelled})

           (retry-job! client job-id)

           (get-job client job-id)
           => (contains {:outcome :waiting})

           @(do-work! client job-type (constantly (success {:some-field "some value"})))
           => truthy

           (get-job client job-id)
           => (contains {:outcome :success}))))


(defn isinstance [c]
  (fn [x] (instance? c x)))

(defn close-to
  ([expected]
   (close-to expected timestamp-tolerance))
  ([expected tolerance-period]
   (let [acceptable-interval (interval (minus expected tolerance-period)
                                       (plus expected tolerance-period))]
     (fn [x] (within? acceptable-interval expected)))))

(fact "Current job state is serialised between server and client as expected"
      (let [job-id (uuid)
            commitment-id (atom "not set")
            job-type (uuid-str)
            tags (set [(uuid-str) (uuid-str)])
            created-time (now)
            claimed-time (plus created-time (millis 5))
            finished-time (plus claimed-time (millis 5))
            due-time (minus created-time (seconds 5))
            request-body {:a-field "field value"}
            commitment  (promise)]

        ; Newly created
        (put-job! client job-id (job-req request-body job-type :tags tags :notes "Some description of the job"))
        => (just {:commitment_agent    nil
                  :commitment_id       nil
                  :contract_claimed    nil
                  :contract_created    (close-to created-time)
                  :contract_due        (close-to due-time)
                  :contract_finished   nil
                  :contract_id         (isinstance UUID)
                  :contract_number     1
                  :dependencies_succeeded 0
                  :dependencies        []
                  :notes               "Some description of the job"
                  :error               nil
                  :job_created         (close-to created-time)
                  :job_id              job-id
                  :last_heartbeat      nil
                  :outcome             :waiting
                  :request_body        request-body
                  :requesting_agent    (isinstance UUID)
                  :result_body         nil
                  :job_type            job-type
                  :tags                (just tags :in-any-order)})
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
                      :result_body       {:some-field "some value"}})))

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