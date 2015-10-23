(ns ^:figwheel-always toshtogo.jobs.search
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require [cljs.core.async :refer [chan <! put!]]
            [om.core :as om]
            [om.dom :as dom]
            [ajax.core :refer [GET]]
            [toshtogo.util.chosen :as chosen]))

(defn get-job-types [<fetched>]
  (GET "api/metadata/job_types"
       {:handler         (fn [response]
                           (put! <fetched> response))

        :keywords?       true

        :response-format :json}))

(defn search-form [{:keys [job-types] :as search} owner {:keys [search-fn]}]
  (reify
    om/IInitState
    (init-state [this]
      {:<job-type-selected>   (chan)
       :<job-status-selected> (chan)
       :<job-type-list>       (chan)
       :<job-select-ready>    (chan)
       :all-job-types         nil})

    om/IWillMount
    (will-mount [this]
      (let [{:keys [<job-type-list> <job-type-selected> <job-status-selected> <job-select-ready>]} (om/get-state owner)]
        (go
          (om/set-state! owner :all-job-types (<! <job-type-list>))
          (<! <job-select-ready>)
          (chosen/init "#job-type-select" <job-type-selected>))

        (get-job-types <job-type-list>)
        (go
          (loop []
            (alt! <job-type-selected> ([v _] (om/update! search :job-types v))
                  <job-status-selected> ([v _] (om/update! search :job-statuses v)))
            (recur)))))

    om/IDidMount
    (did-mount [this]
      (let [{:keys [<job-status-selected>]} (om/get-state owner)]
        (chosen/init "#job-status-select" <job-status-selected>)))

    om/IRenderState
    (render-state [this {:keys [<job-select-ready> all-job-types]}]
      (dom/div nil
        (when all-job-types
          (dom/div #js {:className "form-group"}
            (dom/label #js {:for "job-types"} "Job types:")
            (dom/div #js {:className "input-group"}
              (put! <job-select-ready> all-job-types)
              (apply dom/select #js {:id       "job-type-select"
                                     :name     "job-types"
                                     :multiple true}
                     (map #(dom/option nil %) all-job-types)))))

        (dom/div #js {:className "form-group"}
          (dom/label #js {:for "job-statuses"} "Status:")
          (dom/div #js {:className "input-group"}
            (dom/select #js {:id       "job-status-select"
                             :name     "job-statuses"
                             :multiple true}
              (dom/option nil "cancelled")
              (dom/option nil "error")
              (dom/option nil "running")
              (dom/option nil "success")
              (dom/option nil "waiting"))))

        (dom/div nil
          (dom/button #js {:className "btn btn-success"
                           :onClick   (fn [_] (search-fn search))}
            "Search"))))))
