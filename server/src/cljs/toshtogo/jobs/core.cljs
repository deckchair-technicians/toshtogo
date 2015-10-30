(ns toshtogo.jobs.core
  (:require [om.dom :as dom]
            [om.core :as om]

            [cemerick.url :as url]

            [toshtogo.util.dates :as dates]
            [toshtogo.util.history :as history]
            [toshtogo.components.pager :refer [pager]]
            [toshtogo.jobs.search :as search]
            [toshtogo.jobs.util :as util]))

(defn job-row
  [{:keys [job_id job_type job_created contract_claimed contract_finished outcome home_graph_id] :as job}]
  (dom/tr nil
          (dom/td #js {:className (util/row-classname job)} outcome)
          (dom/td nil (dom/a #js {:href (str "#/graphs/" home_graph_id "/jobs/" job_id)} job_type))
          (dom/td nil (dates/date->day-string job_created))
          (dom/td nil (dates/date->time-string job_created))
          (dom/td nil (dates/date->time-string contract_claimed))
          (dom/td nil (dates/date->time-string contract_finished))))

(defn job-tbody
  [data owner]
  (reify
    om/IRender
    (render [_]
      (apply dom/tbody nil
             (map job-row data)))))

(defn jobs-view [{:keys [jobs search paging query]} _]
  (reify
    om/IRender
    (render [_this]
      (dom/div nil
        (om/build search/search-form {:search search :query query})
        (om/build pager {:paging paging
                         :query query})

        (dom/table #js {:className "table table-striped"}
          (dom/thead nil
            (dom/tr nil
              (dom/th nil "Status")
              (dom/th nil "Job type")
              (dom/th nil "Day")
              (dom/th nil "Created")
              (dom/th nil "Started")
              (dom/th nil "Finished")))
          (om/build job-tbody jobs))

        (om/build pager {:paging paging
                         :query query})))))
