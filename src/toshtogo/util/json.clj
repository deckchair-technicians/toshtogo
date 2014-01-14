(ns toshtogo.util.json
  (:require [cheshire.generate :as json-gen]
            [cheshire.core :as json]))

(json-gen/add-encoder org.joda.time.DateTime json-gen/encode-str)

(defn encode [x]
  (json/encode x))
