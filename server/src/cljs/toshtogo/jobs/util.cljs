(ns toshtogo.jobs.util)

(defn row-classname [job]
  (when (:outcome job)
    (case (keyword (:outcome job))
      :waiting "info"
      :error "danger"
      :running "info"
      :cancelled "warning"
      (name (:outcome job)))))
