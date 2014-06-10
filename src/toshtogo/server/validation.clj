(ns toshtogo.server.validation
  (:import (clojure.lang Keyword)
           (org.joda.time DateTime))
  (:require [schema.core :as s]
            [toshtogo.util.core :refer [uuid?]]))

(defn validated [thing schema]
  (if-let [errors (s/check schema thing)]
    (throw (ex-info "Validation failure"
                    {:cause  :bad-request
                     :data   thing
                     :errors errors}))
    thing))


(def JobRequest
  {:job_type                                   s/Keyword
   :request_body                               (s/pred map? "should be a map")
   (s/optional-key :job_id)                    s/Uuid
   (s/optional-key :fungibility_group_id)      s/Uuid
   (s/optional-key :fungible_under_parent)     s/Bool
   (s/optional-key :tags)                      [s/Keyword]
   (s/optional-key :job_name)                  s/Str
   (s/optional-key :contract_due)              DateTime
   (s/optional-key :notes)                     s/Str
   (s/optional-key :existing_job_dependencies) [s/Uuid]
   (s/optional-key :dependencies)              [(s/recursive #'JobRequest)]})

(def JobResult
  {:outcome                                    (s/enum :success :error :cancelled :try-later :more-work)
   (s/optional-key :result)                    (s/pred map? "should be a map")
   (s/optional-key :error)                     s/Str
   (s/optional-key :existing_job_dependencies) [s/Uuid]
   (s/optional-key :dependencies)              [JobRequest]
   (s/optional-key :contract_due)              DateTime})

(def JobRecord
  {:job_id                    s/Uuid
   :job_type                  s/Keyword
   :requesting_agent          s/Uuid
   :job_created               DateTime
   (s/optional-key :notes)    s/Str
   (s/optional-key :tags)     [s/Keyword]
   :request_body              s/Str
   :fungibility_group_id      s/Uuid
   (s/optional-key :job_name) s/Str
   :home_tree_id              s/Uuid})

(def DependencyRecord
  {:dependency_id s/Uuid
   :link_tree_id  s/Uuid
   :parent_job_id s/Uuid
   :child_job_id  s/Uuid})

(def Agent
  {:hostname       s/Str
   :system_name    s/Str
   :system_version s/Str})