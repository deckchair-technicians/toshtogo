(ns ^:figwheel-always toshtogo.jobs.search
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:require [cljs.core.async :refer [chan <! put!]]
            [om.core :as om]
            [om.dom :as dom]
            [ajax.core :refer [GET]]
            [toshtogo.util.history :as history]))

(defn get-job-types [<fetched>]
  (GET "api/metadata/job_types"
       {:handler         (fn [response]
                           (put! <fetched> response))

        :keywords?       true

        :response-format :json}))

(defn html-coll->seq
  [coll]
  (map
    (fn [i]
      (aget coll i))
    (range 0 (aget coll "length"))))

(defn search-form [{:keys [query] :as data} owner]
  (reify
    om/IInitState
    (init-state [this]
      {:<job-type-list> (chan)})

    om/IWillMount
    (will-mount [this]
      (let [{:keys [<job-type-list>]} (om/get-state owner)]
        (go
          (om/set-state! owner :all-job-types (<! <job-type-list>)))
        (get-job-types <job-type-list>)))

    om/IRenderState
    (render-state [this {:keys [all-job-types]}]
      (dom/div nil
        (when all-job-types
          (dom/div #js {:className "form-group"}
            (dom/label #js {:for "job-types"} "Job types:")
            (dom/div #js {:className "input-group"}
              (apply dom/select #js {:className "form-group"
                                     :name "job-types"
                                     :size 10
                                     :multiple true
                                     :onChange (fn [e]
                                                 (om/update! query :job-types
                                                             (let [selections (.. e -target -selectedOptions)]
                                                               (set (map #(aget % "value") (html-coll->seq selections))))))}
                     (map #(dom/option nil %) all-job-types)))))

        (dom/div #js {:className "form-group"}
          (dom/label #js {:for "job-statuses"} "Status:")
          (dom/div #js {:className "input-group"}
                   (apply dom/div nil
                          (map (fn [field]
                                 (dom/label #js {:className "checkbox-inline"}
                                            (dom/input #js {:type "checkbox"
                                                            :checked (contains? (:outcome query) field)
                                                            :onChange (fn [e]
                                                                        (om/transact! query [:outcome]
                                                                                      (fn [outcome]
                                                                                        (if (.. e -target -checked)
                                                                                          (conj outcome field)
                                                                                          (disj outcome field)))))
                                                            }
                                                       field)))
                               ["cancelled" "error" "running" "success" "waiting"]))))

        (dom/div nil
          (dom/button #js {:className "btn btn-success"
                           :onClick   (fn [_] (history/update-query! query))}
            "Search"))))))
