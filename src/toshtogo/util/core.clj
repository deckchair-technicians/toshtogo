(ns toshtogo.util.core
  (:import (java.util UUID))
  (:require
    [clojure.math.numeric-tower :refer [expt]]
    [clj-time.core :refer [after? now plus millis]]
    [clj-time.format :as tf]
    [clojure.pprint :refer [pprint]]
    [clojure.stacktrace :as stacktrace]))


(defn ppstr [x]
  (with-out-str (pprint x)))

(defn any-pred [& preds]
  (fn [x]
    (some #(% x) preds)))

(defn as-coll [x]
  (if ((any-pred list? seq? vector?) x)
    x
    (if (nil? x)
      []
      [x])))

(defn no-debug
  ([msg x] x)
  ([x] x))

(defn debug
  ([msg x]
   (do (println msg) (debug x)))
  ([x]
   (do
     (if (any-pred list? seq? vector? map?)
       (try (pprint x)
            (catch Throwable e (throw (RuntimeException. (str "could not pprint " x) e))))
       (println x))
     x)))


(defn parse-datetime [s]
  (when s
    (tf/parse (tf/formatters :date-time-parser) s)))

(defn uuid
  ([]
   (UUID/randomUUID))
  ([s]
   (when s (UUID/fromString s))))

(defn uuid-str [] (str (uuid)))

(defn cause-trace
  [e]
  (with-out-str (stacktrace/print-cause-trace e)))

(defn exponential-backoff
  [max-wait-millis retry-count]
  (rand-int
    (min
      max-wait-millis
      (* 100 (expt 2 retry-count)))))

(defn or-exception [func]
  (try
    [(func) nil]
    (catch Throwable e
      [nil e])))

(defn retry-until-success*
  ":sleep-fn takes an integer representing the number of retries and returns number of millis to sleep"
  [func & {:keys [interval sleep-fn timeout max-retries] :or {interval 10} :as opts}]
  (let [interval-fn         (if sleep-fn sleep-fn (fn [i] interval))
        timeout-time        (when timeout (plus (now) (millis timeout)))
        timeout-expired?    (fn [] (and timeout-time (after? (now) timeout-time)))
        max-tries-exceeded? (fn [i] (and max-retries (> i max-retries)))]
    (loop [i 1 result (or-exception func)]
      (if (first result)
        (first result)
        (if (or (timeout-expired?) (max-tries-exceeded? i))
          (throw (RuntimeException. "Giving up on retry" (second result)))
          (do (Thread/sleep (interval-fn i))
              (recur (inc i) (or-exception func))))))))

(defmacro retry-until-success
  [{:keys [interval sleep-fn timeout max-retries] :or {interval 10} :as opts}
   & body]
  `(apply retry-until-success* (fn [] ~@body) (flatten (seq ~opts))))
