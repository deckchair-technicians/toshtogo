(ns toshtogo.util.core
  (:require
    [clojure.math.numeric-tower :refer [expt]]
    [clj-time.core :refer [after? now plus millis interval]]
    [clj-time.format :as tf]
    [clojure.pprint :refer [pprint]]
    [clojure.stacktrace :as stacktrace]
    [clojure.walk :refer [prewalk]]
    [flatland.useful.map :refer [update update-each map-keys]])
  (:import (java.util UUID Map Set)
           (java.util.concurrent TimeoutException)
           (clojure.lang PersistentTreeMap PersistentTreeSet)))

(def sout (agent nil))

(defmacro with-sys-out
  "WARNING: Do not put long-running operations in body. They will lock other operations that
  also want to use sout.

  All operations using *out* will be atomic with other operations
  that use the sout agent"
  [& body]
  `(send-off sout (fn [_#] ~@body)))

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

(defn ensure-seq
  "Ensures a thing is sequential"
  [s]
  (if (sequential? s)
        s
        (if (nil? s) [] [s])))

(defn ppstr [x]
  (with-out-str (pprint x)))

(defn any-pred [& preds]
  (fn [x]
    (some #(% x) preds)))

(defn no-debug
  ([_msg x] x)
  ([x] x))

(defn debug
  ([x]
   (debug nil x))

  ([msg x]
   (with-sys-out
     (when msg (println msg))

     (if (any-pred list? seq? vector? map?)
       (try (pprint x)
            (catch Throwable e (throw (RuntimeException. (str "could not pprint " x) e))))

       (println x)))

   x))


(defn parse-datetime [s]
  (when s
    (tf/parse (tf/formatters :date-time-parser) s)))

(defn uuid
  ([]
   (UUID/randomUUID))
  ([s]
   (when s
     (if (instance? UUID s)
       s
       (UUID/fromString s)))))

(defn uuid-str [] (str (uuid)))

(defn uuid? [x] (instance? UUID x))

(defn cause-trace
  "Finds the root cause exception and returns its stack trace as
   string."
  [e]
  (if-let [cause (.getCause e)]
    (cause-trace cause)
    (with-out-str (stacktrace/print-cause-trace e))))

(defn exponential-backoff
  [max-wait-millis retry-count]
  (let [capped-retry (min 20 retry-count)]
    (min
      max-wait-millis
      (* 100 (expt 2 capped-retry)))))

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

(def default-interval 1000)

(defn retry-until-success*
  ":interval        pause between retries, in millis
   :interval-fn     function that takes an integer for # of retries and return the # of millis to pause
   :timeout         number of millis after which we give up
   :error-fn        function to pass errors to
   :immediate-throw a sequence of exception classes that short-circuit retry cycle"
  [func & {:keys [interval interval-fn timeout max-retries error-fn]
           :or {interval default-interval error-fn nil}}]
  (let [error-fn            (or error-fn (constantly nil))
        interval-fn         (if interval-fn interval-fn (fn [_] interval))
        started             (now)
        elapsed-time        (fn [] (interval started (now)))
        timeout-time        (when timeout (plus (now) (millis timeout)))
        timeout-expired?    (fn [] (and timeout-time (after? (now) timeout-time)))
        max-tries-exceeded? (fn [attempt-number] (and max-retries (>= attempt-number max-retries)))]

    (with-timeout timeout "Giving up on retry"
                  (loop [attempt-number 1
                         [result exception] (or-exception func)]
                    (if (not exception)
                      result
                      (if (or (timeout-expired?) (max-tries-exceeded? attempt-number))
                        (throw (RuntimeException.
                                 (str "Giving up on retry after" attempt-number "attempts and" (elapsed-time))
                                 exception))
                        (do (error-fn exception)
                            (sleep (interval-fn attempt-number))
                            (recur (inc attempt-number) (or-exception func)))))))))

(defmacro retry-until-success
  "opts "
  [opts & body]
  `(apply retry-until-success* (fn [] ~@body) (flatten (seq ~opts))))

(defn safe-name [x]
  (when x
    (if (keyword? x)
      (name x)
      x)))

(defn exception-as-map [e]
  {:stacktrace (cause-trace e)
   :message    (.getMessage e)
   :class      (.getName (class e))
   :ex_data    (ex-data e)})

(defn handle-exception
  "Returns a function which, on exception in root-fn, passes the exception and original arguments to each handler in turn.

  If root-fn is (fn [a b c]), handlers must be (fn [exception-from-root-fn a b c]).

  Returns the result of the first success, or throws the exception from root-fn, on the assumpt that this will contain most
  information about the cause of the problem"
  [root-fn & handlers]
  (reduce (fn [previous-handlers-func next-handler]
            (fn [& args]
              (try
                (apply previous-handlers-func args)
                (catch Throwable root-fn-exception
                  (try
                    (apply next-handler root-fn-exception args)
                    (catch Throwable _always-throw-root-fn-exception
                      (throw root-fn-exception)))))))
          root-fn
          handlers))



