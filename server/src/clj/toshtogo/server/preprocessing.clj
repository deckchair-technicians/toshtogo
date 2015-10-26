(ns toshtogo.server.preprocessing
  (:require [clj-time.core :refer [now]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.util
             [core :refer [uuid ppstr debug]]]))

(defn process-job-tree
      "Breadth-first walk of root and its dependencies.

      1) Applies parent-fn to root
      2) Applies (child-fn root (:dependencies children)
      3) Recurs to process-job-tree for each child"
  [parent-fn child-fn root]
  (let [root (parent-fn root)]
    (if-not (:dependencies root)
      root
      (update root :dependencies #(doall
                                   (->> %
                                        (child-fn root)
                                        (map (fn [child] (process-job-tree parent-fn child-fn child)))))))))

(defn normalise-job-tree
      "Takes a job tree and ensures that the following fields are set on all dependencies:

      :job_id
      :home_tree_id
      :parent_job_id"
  [root-job agent-id]
  (process-job-tree

    (fn [parent]
      (-> parent
          (update :job_id #(or % (uuid)))
          (update :job_type keyword)
          (assoc :requesting_agent agent-id)
          (assoc :job_created (now))))

    (fn [parent children]
      (map (fn [child] (-> child
                           (assoc :home_tree_id (:home_tree_id parent))
                           (assoc :parent_job_id (:job_id parent))))
           children))

    root-job))

(defn find-replacements
      "Looks up equivalent jobs within the specified fungiblity group for each job.

      Returns a map containing:

      :existing-job-ids Matched jobs suitable for replacement
      :new-jobs         Any jobs that were not matched

      This is inefficient- we do it for every job, regardless of where it had a fungibility
      group specified."
  [persistence jobs]
  (reduce (fn [result job]
            (if-let [existing-job (and (:fungibility_key job)
                                       (first
                                         (get-jobs persistence {:job_type        (:job_type job)
                                                                :fungibility_key (conj (:alternative_fungibility_keys job) (:fungibility_key job))
                                                                :fields          [:job_id]})))]
              (update result :existing-job-ids #(cons (:job_id existing-job) %))

              (update result :new-jobs #(cons job %))))

          {:existing-job-ids []
           :new-jobs         []}

          jobs))

(defn replace-fungible-jobs-with-existing-job-ids
      "Walks the dependency tree, removing entries from :dependencies and replacing them
      with :existing_job_dependencies"
  [job-or-contract persistence]
  (process-job-tree
    (fn [parent]
      (let [{:keys [existing-job-ids new-jobs]} (find-replacements persistence (:dependencies parent))]
        (-> parent
            (update :existing_job_dependencies #(concat % existing-job-ids))
            (assoc :dependencies new-jobs))))

    (fn [_ children] children)

    job-or-contract))

(defn collect-dependencies [job]
  (let [child-ids (concat (:existing_job_dependencies job)
                          (map :job_id (:dependencies job)))

        dependency-records (map (fn [child-id] {:link_tree_id  (:home_tree_id job)
                                                :parent_job_id (:job_id job)
                                                :child_job_id  child-id})
                                child-ids)]
    (apply concat
           dependency-records
           (map collect-dependencies (:dependencies job)))))

(defn collect-new-jobs [job]
  (apply concat
         (:dependencies job)
         (map collect-new-jobs (:dependencies job))))
