(ns toshtogo.test.server.api.util
  (:require [toshtogo.util.core :refer [assoc-not-nil]]))

(defn job-req
  [id  body job-type & {:keys [dependencies notes tags]}]
  (assert id)
  (-> {:job_id       id
       :job_type     job-type
       :request_body body}
      (assoc-not-nil :dependencies dependencies
                     :notes notes
                     :tags tags)))

