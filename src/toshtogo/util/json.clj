(ns toshtogo.util.json
  (:import (org.joda.time DateTime)
           (com.fasterxml.jackson.core JsonGenerator))
  (:require [cheshire.generate :as json-gen]
            [cheshire.core :as json]
            [clj-time.format :as tf]
            [toshtogo.util.core :refer [debug]]))

(defn add-jodatime-json-encoder []
  (json-gen/add-encoder
    DateTime
    (fn [^DateTime d ^JsonGenerator jg]
      (.writeString jg (tf/unparse (tf/formatters :date-time) d)))))
(add-jodatime-json-encoder)

(defn encode [m]
  (json/encode m {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSSZZ"}))

(defn decode [s]
  (json/parse-string s keyword))
