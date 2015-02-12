(ns toshtogo.schemas
  "Shared schemas"
  (:require [schema.core :as s]))

(def Agent
  {:hostname       s/Str
   :system_name    s/Str
   :system_version s/Str})
