(ns toshtogo.jobs.core
  (:require [om.dom :as dom]
            [om.core :as om]

            [cemerick.url :as url]

            [toshtogo.util.dates :as dates]
            [toshtogo.util.history :as history]
            [toshtogo.components.pager :refer [pager]]
            [toshtogo.jobs.search :as search]
            [toshtogo.jobs.util :as util]))

(defn job-row [{:keys [job_id job_type job_created contract_claimed contract_finished outcome] :as job}]
  (dom/tr nil
          (dom/td #js {:className (util/row-classname job)} outcome)
          (dom/td nil (dom/a #js {:href (str "#/jobs/" job_id)}
                             job_type))
          (dom/td nil (dates/date->day-string (dates/string->date job_created)))
          (dom/td nil (dates/date->time-string (dates/string->date job_created)))
          (dom/td nil (dates/date->time-string (dates/string->date contract_claimed)))
          (dom/td nil (dates/date->time-string (dates/string->date contract_finished))))
  )

(defn jobs-view [{:keys [jobs search paging]} _ {:keys [base-search-uri]}]
  (reify
    om/IRender
    (render [_this]
      (dom/div nil
        (om/build search/search-form search {:opts {:search-fn (fn [{:keys [job-types job-statuses]}]
                                                                 (println job-types)
                                                                 (history/navigate (str "/jobs?source=" (url/url-encode (str
                                                                                                                          base-search-uri
                                                                                                                          (when (not (empty? job-types))
                                                                                                                            (str "&job_type=" (clojure.string/join "&job_type=" job-types)))

                                                                                                                          (when (not (empty? job-statuses))
                                                                                                                            (str "&outcome=" (clojure.string/join "&outcome=" job-statuses))))))))}})
        (om/build pager paging {:opts {:navigate #(history/navigate (str "/jobs?source=" (url/url-encode %)))}})

        (dom/table #js {:className "table table-striped"}
          (dom/thead nil
            (dom/tr nil
              (dom/th nil "Status")
              (dom/th nil "Job type")
              (dom/th nil "Day")
              (dom/th nil "Created")
              (dom/th nil "Started")
              (dom/th nil "Finished")))
          (apply dom/tbody nil
                 (map (fn [job]
                        (job-row job))
                      jobs)))

        (om/build pager paging {:opts {:navigate #(history/navigate (str "/jobs?source=" (url/url-encode %)))}})))))
