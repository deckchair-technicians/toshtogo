(ns toshtogo.util.json
  (:require [cheshire
             [core :as json]
             [generate :as json-gen]]

            [clj-time
             [core :as t]
             [format :as tf]]
            [schema.utils :as utils]

            [toshtogo.util.core :refer [debug]])

  (:import [org.joda.time DateTime LocalDate DateMidnight]
           [com.fasterxml.jackson.core JsonGenerator]
           [java.io InputStream]
           [schema.utils ValidationError]))


; Let's add a default encoder.
(json-gen/add-encoder
  Object
  (fn [^Object o ^JsonGenerator jg]
    (.writeString jg (.toString o))))

(json-gen/add-encoder
  DateTime
  (fn [^DateTime d ^JsonGenerator jg]
    (.writeString jg ^String (tf/unparse (tf/formatters :date-time) d))))

(json-gen/add-encoder
  DateMidnight
  (fn [^DateMidnight d ^JsonGenerator jg]
    (.writeString jg ^String (tf/unparse (tf/formatters :date) d))))

(json-gen/add-encoder
 LocalDate
 (fn [^LocalDate d ^JsonGenerator jg]
   (.writeString
     jg
     ^String (tf/unparse
               (tf/formatters :date)
               (t/date-time (t/year d) (t/month d) (t/day d))))))

(json-gen/add-encoder
  ValidationError
  (fn [^ValidationError v ^JsonGenerator jg]
    (.writeString jg (pr-str v))))

(defn encode [m]
  (json/encode m {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSSZZ"}))

(defmulti decode class)
(defmethod decode String [s]
  (try
    (json/parse-string s keyword)
    (catch Throwable e
      (throw (RuntimeException. (str "Could not parse:" s) e)))))

(defmethod decode InputStream [s]
  (decode (slurp s)))
(defmethod decode nil [_] nil)
