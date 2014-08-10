(ns toshtogo.util.json
  (:require [schema.utils :refer :all]

            [cheshire
             [core :As json]
             [generate :as json-gen]]

            [clj-time
             [core :as t]
             [format :as tf]]

            [toshtogo.util.core :refer [debug]])

  (:import (org.joda.time DateTime LocalDate DateMidnight)
           (com.fasterxml.jackson.core JsonGenerator)
           (java.io InputStream)
           (schema.utils ValidationError)))

(json-gen/add-encoder
  DateTime
  (fn [^DateTime d ^JsonGenerator jg]
    (.writeString jg (tf/unparse (tf/formatters :date-time) d))))

(json-gen/add-encoder
  DateMidnight
  (fn [^DateMidnight d ^JsonGenerator jg]
    (.writeString jg (tf/unparse (tf/formatters :date) d))))

(json-gen/add-encoder
 LocalDate
 (fn [^LocalDate d ^JsonGenerator jg]
   (.writeString
    jg
    (tf/unparse
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
(defmethod decode nil [s] nil)
