(ns toshtogo.util.schema
  (:require [schema.core :as s]
            [schema.macros :as macros]))

; Fixes some problem with nesting recursive schemas inside other recursive schemas.
; No time to work out what's actually going on, but this fixes it.
; See inline comment below for details
(declare recursive)
(do
  (defn var-name [v]
    (let [{:keys [ns name]} (meta v)]
      (symbol (str (ns-name ns) "/" name))))

  (defrecord Recursive [schema-var]
    s/Schema
    (walker [this]
      (let [walker-atom (atom nil)]
        (reset! walker-atom (s/start-walker
                              (let [oldwalker s/subschema-walker]
                                (fn [schema]

                                  ; USED TO LOOK LIKE THIS:
                                  ;(if (= schema this) ; <- this wasn't evaluating to true in our use case
                                  ;  #(@walker-atom %)
                                  ;  (oldwalker schema))
                                  (if (and
                                        (instance? Recursive schema)
                                        (= (:schema-var schema) (:schema-var this)))
                                    #(@walker-atom %)
                                    (oldwalker schema))))

                              @schema-var))))
    (explain [this]
      (list 'recursive (list 'var (var-name schema-var)))))

  (defn recursive
    "Support for (mutually) recursive schemas by passing a var that points to a schema,
       e.g (recursive #'ExampleRecursiveSchema)."
    [schema-var]
    (when-not (var? schema-var)
      (macros/error! (str "Not a var: " schema-var)))
    (Recursive. schema-var)))

