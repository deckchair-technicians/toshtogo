(ns toshtogo.test.handler
  (:require [clj-time.core :refer [now minutes plus after?]]
            [midje.sweet :refer :all]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.java.jdbc :as sql]
            [toshtogo.web.handler :refer [app]]
            [toshtogo.client :refer :all]
            [toshtogo.client.http :refer :all]
            [toshtogo.api :refer [success error add-dependencies try-later]]
            [toshtogo.util.core :refer [uuid uuid-str debug]]))

(with-redefs [toshtogo.client/heartbeat-time 1]

  #_(def client (http-sender-client "http://localhost:3000/"))

  (def client (app-sender-client app))

  (defn return-success [job] (success {:result 1}))

  (fact "Work can be requested"
    (let [job-id (uuid)
          tag    (uuid-str)]

      (put-job! client job-id {:tags [tag]
                               :request_body {:a-field "field value"}})

      (request-work! client [tag]) => (contains {:job_id (str job-id)
                                                 :request_body {:a-field "field value"}})))

  (fact "Work can only be requested once"
    (let [job-id (uuid)
          tag    (uuid-str)]

      (put-job! client job-id (job-req {:a-field "field value"} [tag]))

      (request-work! client [tag])
      (request-work! client [tag]) => nil))

  (fact "Agents can request work and then complete it"
    (let [job-id (uuid)
          tag    (uuid-str)]

      (put-job! client job-id (job-req {:a-field "field value"} [tag]))

      (let [func                      (fn [job] (success {:response-field "all good"}))
            {:keys [contract result]} @(do-work! client [tag] func)]
        contract
        => (contains {:job_id (str job-id) :request_body {:a-field "field value"}})
        result
        => (contains {:outcome :success :result {:response-field "all good"}}))

      (get-job client job-id)
      => (contains {:outcome "success" :result_body {:response-field "all good"}})))

  (fact "Agents can report errors"
    (let [job-id (uuid)
          tag    (uuid-str)]

      (put-job! client job-id (job-req {:a-field "field value"} [tag]))

      (let [func                      (fn [job] (error "something went wrong"))
            {:keys [contract result]} @(do-work! client [tag] func)]
        contract
        => (contains {:job_id (str job-id) :request_body {:a-field "field value"}})
        result
        => (contains {:outcome :error :error "something went wrong"}))

      (get-job client job-id)
      => (contains {:outcome "error" :error "something went wrong"})))

  (fact "Client can report unhandled exceptions"
    (let [job-id (uuid)
          tag    (uuid-str)]

      (put-job! client job-id (job-req {:a-field "field value"} [tag]))

      (let [func                      (fn [job] (throw (Exception. "WTF")))
            {:keys [contract result]} @(do-work! client [tag] func)]
        contract
        => (contains {:job_id (str job-id) :request_body {:a-field "field value"}})
        result
        => (contains {:outcome :error :error (contains "WTF")}))

      (get-job client job-id)
      => (contains {:outcome "error" :error (contains "WTF")})))

  (facts "Jobs can have dependencies"
    (let [job-id (uuid)
          parent-tag    (uuid-str)
          child-tag     (uuid-str)]

      (put-job!
       client
       job-id (job-req
               {:a "field value"} [parent-tag]
               [(job-req {:b "child one"} [child-tag])
                (job-req {:b "child two"} [child-tag])]))

      (fact "No contract is created for parent job"
        (request-work! client [parent-tag]) => nil)

      (let [func (fn [job] (success (job :request_body)))]
        (fact "Dependencies are executed in order"
          (:contract @(do-work! client [child-tag] func))
          => (contains {:request_body {:b "child one"}}))

        (fact "Parent job is not ready until all dependencies complete"
          (request-work! client [parent-tag]) => nil
          (get-job client job-id) => (contains {:contracts_completed 1}))

        @(do-work! client [child-tag] func)
        (get-job client job-id) => (contains {:contracts_completed 2})

        (fact (str "Parent job is released when dependencies are complete, "
                   "with dependency responses merged into its request")
          (let [contract (request-work! client [parent-tag])]
            contract
            => (contains {:request_body {:a "field value"}})

            (contract :dependencies)
            => (contains [(contains {:result_body {:b "child one"}})
                          (contains {:result_body {:b "child two"}})]
                         :in-any-order))))))

  (facts "Requesting more work"
    (let [job-id (uuid)
          parent-tag    (uuid-str)
          child-tag     (uuid-str)]

      (put-job! client job-id (job-req {:parent-job "parent job"} [parent-tag]))

      (let [add-deps       (fn [job]
                             (add-dependencies
                              (job-req {:first-dep "first dep"} [child-tag])
                              (job-req {:second-dep "second dep"} [child-tag])))
            complete-child (fn [job] (success (job :request_body)))]

        @(do-work! client [parent-tag] add-deps) => truthy

        (fact "Parent job is not ready until new dependencies complete"
          (request-work! client [parent-tag]) => nil)

        @(do-work! client [child-tag] complete-child) => truthy
        @(do-work! client [child-tag] complete-child) => truthy

        (fact (str "Parent job is released when dependencies are complete, "
                   "with dependency responses merged into its request")
          (let [contract (request-work! client [parent-tag])]
            contract
            => (contains {:request_body {:parent-job "parent job"}})

            (contract :dependencies)
            => (contains [(contains {:result_body {:first-dep "first dep"}})
                          (contains {:result_body {:second-dep "second dep"}})]
                         :in-any-order))))))


  (facts "Requesting more work"
    (let [job-id     (uuid)
          parent-tag (uuid-str)
          child-tag  (uuid-str)]

      (put-job! client job-id (job-req {:parent-job "parent job"} [parent-tag]))

      (let [add-deps       (fn [job]
                             (add-dependencies
                              (job-req {:first-dep "first dep"} [child-tag])
                              (job-req {:second-dep "second dep"} [child-tag])))
            complete-child (fn [job] (success (job :request_body)))]

        @(do-work! client [parent-tag] add-deps) => truthy

        (fact "Parent job is not ready until new dependencies complete"
          (request-work! client [parent-tag]) => nil)

        @(do-work! client [child-tag] complete-child) => truthy
        @(do-work! client [child-tag] complete-child) => truthy

        (fact (str "Parent job is released when dependencies are complete, "
                   "with dependency responses merged into its request")
          (let [contract (request-work! client [parent-tag])]
            contract
            => (contains {:request_body {:parent-job "parent job"}})

            (contract :dependencies)
            => (contains [(contains {:result_body {:first-dep "first dep"}})
                          (contains {:result_body {:second-dep "second dep"}})]
                         :in-any-order))))))

  (facts "Try again later"
    (let [job-id          (uuid)
          job-tag         (uuid-str)
          before-due-time (now)
          due-time        (plus before-due-time (minutes 1))]

      (put-job! client job-id (job-req [] [job-tag]))

      (let [delay (fn [job] (try-later due-time "some error happened"))]
        @(do-work! client [job-tag] delay)) => truthy

        (request-work! client [job-tag]) => nil
        (provided (now) => before-due-time)

        @(do-work! client [job-tag] return-success) => truthy
        (provided (now) => due-time)))

  (facts "Heartbeats get stored, but only if they are more recent than the current heartbeat."
    (let [job-id         (uuid)
          job-tag        (uuid-str)
          start-time-ish (now)]

      (put-job! client job-id (job-req [] [job-tag]))

      @(do-work! client [job-tag] (fn [job] (Thread/sleep 1) (success "Oh yeah")))

      (let [{:keys [last_heartbeat]} (get-job client job-id)]
        (after? last_heartbeat start-time-ish ) => truthy))))
