(ns toshtogo.util.core
  (:import (java.util UUID)
           (java.util.concurrent TimeoutException))
  (:require
    [clojure.math.numeric-tower :refer [expt]]
    [clj-time.core :refer [after? now plus millis interval]]
    [clj-time.format :as tf]
    [clojure.pprint :refer [pprint]]
    [clojure.stacktrace :as stacktrace]))

(defn assoc-not-nil
  ([m key val]
   (if val
     (assoc m key val)
     m))
  ([m key val & kvs]
   (let [ret (assoc-not-nil m key val)]
     (if kvs
       (recur ret (first kvs) (second kvs) (nnext kvs))
       ret))))

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
  (min
    max-wait-millis
    (* 100 (expt 2 retry-count))))

(defn or-exception [func]
  (try
    [(func) nil]
    (catch Throwable e
      [nil e])))

(defn sleep [millis]
  (Thread/sleep millis))

(defmacro with-timeout [timeout-ms message & body]
  (assert body)
  `(if (nil? ~timeout-ms)
     (do ~@body)
     (let [f# (future-call (fn [] ~@body))]

       (deref f# ~timeout-ms nil)

       (if (realized? f#)
         @f#
         (do
           (future-cancel f#)
           (throw (TimeoutException. ~message)))))))

(defn retry-until-success*
  ":interval        pause between retries, in millis
   :interval-fn     function that takes an integer for # of retries and return the # of millis to pause
   :timeout         number of millis after which we give up
   :error-fn        function to pass errors to"
  [func & {:keys [interval interval-fn timeout max-retries error-fn] :or {interval 10 error-fn nil} :as opts}]
  (let [error-fn (or error-fn (constantly nil))
        interval-fn (if interval-fn interval-fn (fn [i] interval))
        started (now)
        elapsed-time (fn [] (interval started (now)))
        timeout-time (when timeout (plus (now) (millis timeout)))
        timeout-expired? (fn [] (and timeout-time (after? (now) timeout-time)))
        max-tries-exceeded? (fn [attempt-number] (and max-retries (>= attempt-number max-retries)))]

    (with-timeout timeout "Giving up on retry"
                  (loop [attempt-number 1
                         result (or-exception func)]
                    (if (first result)
                      (first result)
                      (if (or (timeout-expired?) (max-tries-exceeded? attempt-number))
                        (throw (RuntimeException.
                                 (str "Giving up on retry after" attempt-number "attempts and" (elapsed-time))
                                 (second result)))
                        (do (error-fn (second result))
                            (sleep (interval-fn attempt-number))
                            (recur (inc attempt-number) (or-exception func)))))))))

(defmacro retry-until-success
  "opts "
  [opts & body]
  `(apply retry-until-success* (fn [] ~@body) (flatten (seq ~opts))))
