(ns toshtogo.test.functional.benchmark-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :as t]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.test.functional.test-support :refer :all]
            [toshtogo.client.agent :refer :all]
            [toshtogo.client.protocol :refer :all]))

(def persistent-client (test-client :timeout 100000))

(defn write-csv [path row-data]
  (let [columns (into [] (distinct (apply concat (map keys row-data))))
        headers (map name columns)
        rows (mapv #(mapv % columns) row-data)]
    (with-open [file (io/writer path)]
      (csv/write-csv file (cons headers rows)))))

(defn mean
  [values]
  (/ (apply + values) (count values)))

(defn standard-deviation [values]
  (let [avg (mean values)
        squares (for [x values]
                  (let [x-avg (- x avg)]
                    (* x-avg x-avg)))
        total (count values)]
    (-> (/ (apply + squares)
           (- total 1))
        (Math/sqrt))))

(defmacro timer
  [expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (/ (double (- (. System (nanoTime)) start#)) 1000000.0)))

(defn generate-dependent-job
  [job child-job-type id]
  (-> (job-req {:b (str "child " job)} child-job-type)
      (with-job-id id)))

(defn get-job-by-id [{:keys [number-of-dependent-jobs]}]
  (let [job-id (uuid)
        parent-job-type (uuid-str)
        child-job-type (uuid-str)
        child-job-seq (take number-of-dependent-jobs (range))
        child-job-type-seq (take number-of-dependent-jobs (repeat child-job-type))
        child-id-seq (take number-of-dependent-jobs (repeatedly #(uuid)))
        dep-seq (map generate-dependent-job
                     child-job-seq
                     child-job-type-seq
                     child-id-seq)
        actual-job-id (uuid)]

    (put-job!
      persistent-client
      job-id (job-req
               {:a "field value"} parent-job-type
               :dependencies dep-seq))

    (deref (future (while (-> (get-job client (last child-id-seq))
                              :outcome
                              (not= :success))
                     (Thread/sleep 100)))
           2000 nil)

    {:number-of-dep-jobs number-of-dependent-jobs
     :time-ms (timer (get-job client actual-job-id))}))

(defn generate-profile
  [number-of-iterations f args]
  (let [s #{}
        profile (into s (for
                          [iteration (map inc (range number-of-iterations))]
                          (assoc
                            (apply f args)
                            :iteration
                            iteration)))]
    (sort-by :iteration profile)))

(background
  (before :contents (reset-dev-db)))

(fact "Check the mean time of the get-job query with one job with a large number of dependencies"
      (let [number-of-iterations-to-run 10
            number-of-dependent-jobs 10
            benchmark-in-ms 10
            get-job-profile (generate-profile number-of-iterations-to-run
                                              get-job-by-id
                                              [{:number-of-dependent-jobs number-of-dependent-jobs}])
            mean-job-time (mean (map :time-ms get-job-profile))]

        (< mean-job-time benchmark-in-ms) => truthy))

(fact "Check the standard deviation of the time of the get-job query with one job with a large number of dependencies"
      (let [number-of-iterations-to-run 10
            number-of-dependent-jobs 10
            benchmark-in-ms 10
            get-job-profile (generate-profile number-of-iterations-to-run
                                              get-job-by-id
                                              [{:number-of-dependent-jobs number-of-dependent-jobs}])
            stand-dev-job-time (standard-deviation (map :time-ms get-job-profile))]

        (< stand-dev-job-time benchmark-in-ms) => truthy))

