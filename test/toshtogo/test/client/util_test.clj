(ns toshtogo.test.client.util-test
  (:import [toshtogo.client SenderException]
           (java.util.concurrent ExecutionException))
  (:require [midje.sweet :refer :all]
            [flatland.useful.map :refer [into-map]]
            [toshtogo.client.util :refer [throw-500]]))

(fact "throw-500 works"
      (throw-500 {:status 500}) => (throws SenderException)
      (throw-500 {:status 200}) => {:status 200}
      (throw-500 nil) => nil)


(defn _500-a-few-times [return-value & {:keys [failure-count] :or {failure-count 5}}]
  (let [a (atom 0)]
    (fn []
      (if (<= (swap! a inc) failure-count)
        {:status 500 :body (str "Error #" @a)}
        return-value))))

(defn _500-for [millis return-value]
  (let [nanos           (* millis 1000000)
        start           (System/nanoTime)
        should-succeed? #(> (- (System/nanoTime) start) nanos)]
    (fn []
      (if (should-succeed?)
        return-value
        (do (Thread/sleep 100) {:status 500 :body (str "Not ready for successful response yet")})))))

(defn until-successful-response
  "Ensures tests never hang"
  [func & opts]
  (let [f (future (toshtogo.client.util/until-successful-response (into-map opts) (func)))
        result (deref f 2000 "Never returned")]
    (when (= "Never returned" result)
      (future-cancel f))
    result
    ))

(fact "until-successful-response keeps retrying until it gets a success"
      (until-successful-response (_500-a-few-times {:status 200})) => {:status 200}
      (until-successful-response (_500-a-few-times "result")) => "result"

      ; timeout
      (until-successful-response (_500-for 100 "no timeout")) => "no timeout"
      (until-successful-response (_500-for 1500 "does not succeed in time") :timeout 10) => (throws ExecutionException)
      (until-successful-response (constantly "succeeds in time") :timeout 100) => "succeeds in time")
