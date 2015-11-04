(ns toshtogo.jobs.job
  (:require [om.dom :as dom]
            [om.core :as om]
            [cljs.core.async :refer [chan <! put!]]
            [ajax.core :refer [POST]]
            [toshtogo.jobs.util :as util]
            [toshtogo.components.panel :as components.panel]
            [clojure.string :as s]
            [cljs-time.core :as t]
            [cljs-time.format :as tf]))

(defn json-view [selector m]
  (.JSONView (js/$ selector) (clj->js m)))

(defn update-json
  [{:keys [request_body result_body error] :as job}]
  (json-view "#job-json" (dissoc job :request_body :result_body :error))
  (json-view "#job-request" request_body)
  (json-view "#job-result" (if error error result_body)))

(defn label [job]
  (dom/span #js {:className (str "label label-" (util/row-classname job))}
    (when (:outcome job) (name (:outcome job)))))

(defn title [{:keys [job_name job_type] :as job}]
  (if job_name
    (dom/div nil
      (dom/h1 nil job_name)
      (dom/h2 nil job_type
              " "
              (label job)))
    (dom/h1 nil job_type
            " "
            (label job))))

(defn act! [action {:keys [job-id graph-id]} <messages>]
  (POST (str "/api/jobs/" job-id "?action=" action)
        {:params {:agent {:hostname       "?"
                          :system_name    "toshtogo-ui"
                          :system_version "?"}}
         :format :json
         :handler         (fn [response]
                            (put! <messages> [:job-modified {:response response
                                                             :job-id job-id
                                                             :graph-id graph-id}]))

         :error-handler   (fn [response]
                            (put! <messages> [:failure {:response response}]))}))

(defn actions [{:keys [job_id outcome home_graph_id]} <messages>]
  (let [job {:job-id job_id :graph-id home_graph_id}]
    (case outcome
      :running (dom/button #js {:className "btn btn-danger"
                                :onClick   (fn [_] (act! "pause" job <messages>))}
                           "Pause")
      :waiting (dom/button #js {:className "btn btn-danger"
                                :onClick   (fn [_] (act! "pause" job <messages>))}
                           "Pause")
      :cancelled (dom/button #js {:className "btn btn-success"
                                  :onClick   (fn [_] (act! "retry" job <messages>))}
                             "Retry")
      :error (dom/button #js {:className "btn btn-success"
                              :onClick   (fn [_] (act! "retry" job <messages>))}
                         "Retry")
      nil)))

(def formatter
  (tf/formatter "yyyy-MM-dd HH:mm:ss"))

(defn format-date-string
  [s]
  (when s
    (tf/unparse formatter
                (tf/parse s))))

(defn job-view [job]
  (reify
    om/IDidMount
    (did-mount [_this]
      (update-json job))

    om/IDidUpdate
    (did-update [_this _ _]
      (update-json job))

    om/IRenderState
    (render-state [_this {:keys [<messages>]}]
      (when job
        (dom/div #js {:className ""}

                 (title job)

                 (actions job <messages>)

                 (dom/div #js {:className "row"}
                          (dom/div #js {:className "col-md-12"}
                                   (let [contracts (select-keys job [:contract_created :contract_claimed :contract_finished])
                                         latest-contract (first (sort-by second > contracts))]
                                     (om/build components.panel/panel
                                               {:heading "Information"
                                                :content (dom/div nil
                                                                  (dom/div #js {:className "row"}
                                                                           (dom/label #js {:className "col-md-2"
                                                                                           :htmlFor "job-created"}
                                                                                      "Job created")
                                                                           (dom/div #js {:className "col-md-8"}
                                                                                    (format-date-string (:job_created job))))

                                                                  (dom/div #js {:className "row"}
                                                                           (dom/label #js {:className "col-md-2"}
                                                                                      (s/capitalize (s/replace (name (first latest-contract)) #"_" " ")))
                                                                           (dom/div #js {:className "col-sm-8"}
                                                                                    (format-date-string (second latest-contract))))

                                                                  (dom/div #js {:className "row"}
                                                                           (dom/label #js {:className "col-md-2"
                                                                                           :htmlFor "job-created"}
                                                                                      "Contract due")
                                                                           (dom/div #js {:className "col-sm-8"}
                                                                                    (format-date-string (:contract_due job)))))})))
                          (dom/div #js {:className "col-md-6"}
                                   (om/build components.panel/panel
                                             {:heading "Request"
                                              :content (dom/div #js {:id "job-request"})}))
                          (dom/div #js {:className "col-md-6"}
                                   (om/build components.panel/panel
                                             {:heading "Response"
                                              :content (dom/div #js {:id "job-result"})})))

                 (dom/div #js {:className "row"}
                          (dom/div #js {:className "col-md-12"}
                                   (om/build components.panel/panel
                                             {:heading "Full state"
                                              :content (dom/div #js {:id "job-json"})}
                                             {:init-state {:collapsed? true}}))))))))
