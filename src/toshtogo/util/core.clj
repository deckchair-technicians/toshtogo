(ns toshtogo.util.core
  (:require
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
       (if (any-pred list? seq?  vector? map?)
         (try (pprint x)
              (catch Throwable e (throw (RuntimeException. (str "could not pprint " x) e))))
         (println x))
       x)))


(defn parse-datetime [s]
  (when s
    (tf/parse (tf/formatters :date-time-parser) s)))

(defn uuid
  ([]
     (java.util.UUID/randomUUID))
  ([s]
     (when s (java.util.UUID/fromString s))))

(defn uuid-str [] (str (uuid)))

(defn cause-trace
  [e]
  (with-out-str (stacktrace/print-cause-trace e)))
