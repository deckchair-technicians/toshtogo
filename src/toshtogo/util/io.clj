(ns toshtogo.util.io
   (:require [clojure.java.io :as jio])
   (:import [java.io ByteArrayOutputStream ByteArrayInputStream InputStream]))

(defn byte-array-output!
  [^InputStream input-stream]
  (let [^ByteArrayOutputStream result (ByteArrayOutputStream.) ]
    (jio/copy input-stream result)
    result))

(defn byte-array-input!
  [^InputStream input-stream]
   (ByteArrayInputStream. (.toByteArray (byte-array-output! input-stream) )))
