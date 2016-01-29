(ns toshtogo.examples.long-running
  (:require [toshtogo.client
             [protocol :as tt]
             [middleware :as ttm]
             [util :as ttu]]))

(defn handle
  [{:keys [run-for-ms]}]
  (Thread/sleep run-for-ms)
  (tt/success {}))

(defn wrap-check-dependencies [handler]
  (fn [{:keys [dependency-count depth run-for-ms] :as request}]
    (if (or (= dependency-count (count (:long-running request)))
            (= 1 depth))
      (handler request)
      (apply tt/add-dependencies (take dependency-count (repeat (tt/job-req {:dependency-count dependency-count
                                                                             :depth            (dec depth)
                                                                             :run-for-ms       run-for-ms}
                                                                            :long-running)))))))

(def handler
  (-> handle
      (wrap-check-dependencies)
      (ttm/wrap-extract-request)
      (ttm/wrap-merge-dependency-results :job-type->merger {:long-running ttu/concat-results-of-multiple-jobs})
      ))