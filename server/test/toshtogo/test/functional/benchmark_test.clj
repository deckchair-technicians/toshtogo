(ns toshtogo.test.functional.benchmark-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :as t]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.client.agent :refer :all]
            [toshtogo.client.protocol :refer :all]))

(defn generate-dependent-job
  [job child-job-type id]
  (-> (job-req {:b (str "child " job)} child-job-type)
      (with-job-id id)))

(background
  (before :contents (reset-dev-db)))

(with-redefs [toshtogo.client.protocol/heartbeat-time 10]

  (fact "Time get-job query with one job with a large number of dependencies"
    (let [number-of-dep-jobs 100
          job-id (uuid)
          parent-job-type (uuid-str)
          child-job-type (uuid-str)
          child-job-seq (take number-of-dep-jobs (range))
          child-job-type-seq (take number-of-dep-jobs (repeat child-job-type))
          child-id-seq (take number-of-dep-jobs (repeatedly #(uuid)))
          dep-seq (map generate-dependent-job
                       child-job-seq
                       child-job-type-seq
                       child-id-seq)
          actual-job-id (uuid)]

      (println :timimg-put)
      (time
        (put-job!
          client
          job-id (job-req
                   {:a "field value"} parent-job-type
                   :dependencies dep-seq)))

      (deref (future (while (-> (get-job client (last child-id-seq))
                                :outcome
                                (not= :success))
                       (Thread/sleep 100)))
             2000 nil)

      (println :actual (time (get-job client actual-job-id)))
      ))
  )