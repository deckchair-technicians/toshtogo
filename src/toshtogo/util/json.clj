(ns toshtogo.util.json
  (:require [schema.utils :refer :all]
            [cheshire.generate :as json-gen]
            [cheshire.core :as json]
            [clj-time.format :as tf]
            [toshtogo.util.core :refer [debug]])

  (:import (org.joda.time DateTime)
           (com.fasterxml.jackson.core JsonGenerator)
           (java.io InputStream)
           (schema.utils ValidationError)))

(json-gen/add-encoder
  DateTime
  (fn [^DateTime d ^JsonGenerator jg]
    (.writeString jg (tf/unparse (tf/formatters :date-time) d))))

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