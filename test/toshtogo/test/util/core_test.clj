(ns toshtogo.test.util.core-test
  (:import (java.util.concurrent ExecutionException))
  (:require [flatland.useful.map :refer [into-map]]
            [midje.sweet :refer :all]
            [toshtogo.util.core :refer :all]))

(defn fail-a-few-times [return-value & {:keys [failure-count] :or {failure-count 5}}]
  (let [a (atom 0)]
    (fn []
      (if (<= (swap! a inc) failure-count)
        (throw (RuntimeException. (str @a)))
        return-value))))

(defn fail-for [millis return-value]
  (let [nanos (* millis 1000000)
        start (System/nanoTime)
        should-succeed? #(> (- (System/nanoTime) start) nanos)]
    (fn []
      (if (should-succeed?)
        return-value
        (do (Thread/sleep 100) (throw (RuntimeException. "Not time to succeed yet")))))))

(defn attempt
  "Ensures tests never hang"
  [func & opts]
  (let [f (future (retry-until-success (into-map opts) (func)))
        result (deref f 2000 "Never returned")]
    (when (= "Never returned" result)
      (future-cancel f))
    result
    ))

(fact "retry-until-success"
      (attempt (fail-a-few-times "result")) => "result"

      ; max-retries
      (attempt (fail-a-few-times "exceeds retry count" :failure-count 4) :max-retries 4) => (throws ExecutionException)
      (attempt (fail-a-few-times "succeeds on last attempt" :failure-count 3) :max-retries 4) => "succeeds on last attempt"

      ; timeout
      (attempt (fail-for 100 "no timeout")) => "no timeout"
      (attempt (fail-for 1500 "does not succeed in time") :timeout 10) => (throws ExecutionException)
      (attempt (constantly  "succeeds in time") :timeout 100) => "succeeds in time"

      ; interval
      (attempt (fail-a-few-times "succeeds after some sleeps" :failure-count 3) :interval 10)
      => "succeeds after some sleeps"
      (provided (toshtogo.util.core/sleep 10) => nil :times 3)

      ; interval-fn
      (attempt (fail-a-few-times "succeeds after some sleeps" :failure-count 3) :interval-fn #(* 10 %))
      => "succeeds after some sleeps"
      (provided (toshtogo.util.core/sleep 10) => nil :times 1
                (toshtogo.util.core/sleep 20) => nil :times 1
                (toshtogo.util.core/sleep 30) => nil :times 1
                (toshtogo.util.core/sleep 40) => nil :times 0))