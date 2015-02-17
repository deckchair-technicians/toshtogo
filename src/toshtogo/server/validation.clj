(ns toshtogo.server.validation
  (:require [toshtogo.util
             [core :refer [uuid? ensure-seq]]
             [schema :as toshtogo-schema]]

            [schema.core :as s]

            [vice
             [coerce :refer [errors]]
             [valuetypes :refer [JodaDateTime Uuid]]]))

(defn validated [thing schema]
  (if-let [errors (s/check schema thing)]
    (throw (ex-info (str "Validation failure- " (str (first (ensure-seq errors))))
                    {:cause  :bad-request
                     :data   thing
                     :errors errors}))
    thing))

(defn matches-schema?
  [schema x]
  (nil? (errors schema x)))

(def JobRequest
  {:job_type                                   s/Keyword
   :request_body                               (s/pred map? "should be a map")
   (s/optional-key :job_id)                    Uuid
   (s/optional-key :fungibility_group_id)      Uuid
   (s/optional-key :fungible_under_parent)     s/Bool
   (s/optional-key :tags)                      [s/Keyword]
   (s/optional-key :job_name)                  s/Str
   (s/optional-key :contract_due)              JodaDateTime
   (s/optional-key :notes)                     s/Str
   (s/optional-key :existing_job_dependencies) [Uuid]
   (s/optional-key :dependencies)              [(toshtogo-schema/recursive #'JobRequest)]})

(def ContractOutcome
  (s/enum :success :error :cancelled :try-later :more-work))

(def JobResult
  {:outcome                                    ContractOutcome
   (s/optional-key :result)                    {s/Any s/Any}
   (s/optional-key :error)                     {(s/optional-key :message) (s/maybe s/Str)
                                                (s/optional-key :stacktrace) s/Str
                                                s/Any s/Any}
   (s/optional-key :existing_job_dependencies) [Uuid]
   (s/optional-key :dependencies)              [JobRequest]
   (s/optional-key :contract_due)              JodaDateTime})

(def JobRecord
  {:job_id                    s/Uuid
   :job_type                  s/Keyword
   :requesting_agent          Uuid
   :job_created               JodaDateTime
   (s/optional-key :notes)    s/Str
   (s/optional-key :tags)     [s/Keyword]
   :request_body              s/Str
   :fungibility_group_id      Uuid
   (s/optional-key :job_name) s/Str
   :home_tree_id              Uuid})

(def DependencyRecord
  {:dependency_id Uuid
   :link_tree_id  Uuid
   :parent_job_id Uuid
   :child_job_id  Uuid})

(def UniqueConstraintException
  {:cause (s/eq :unique-constraint-exception)})
