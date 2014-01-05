(ns toshtogo.util)

(defn uuid
  ( []
      (java.util.UUID/randomUUID))
  ( [s]
      (java.util.UUID/fromString s)))
