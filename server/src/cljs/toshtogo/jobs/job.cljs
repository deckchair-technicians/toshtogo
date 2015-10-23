(ns toshtogo.jobs.job
  (:require [om.dom :as dom]
            [om.core :as om]
            [cljs.core.async :refer [chan <! put!]]
            [ajax.core :refer [POST]]
            [toshtogo.jobs.util :as util]
            [toshtogo.util.history :as history]))

(defn json-view [selector m]
  (.JSONView (js/$ selector) (clj->js m)))

(defn update-json [{:keys [request_body result_body] :as job}]
  (json-view "#job-json" job)
  (json-view "#job-request" request_body)
  (json-view "#job-result" result_body))

(defn panel [heading content]
  (dom/div #js {:className "panel panel-default"}
    (dom/div #js {:className "panel-heading"}
      heading)
    (dom/div #js {:className "panel-body"}
      content)))

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

(defn act! [action job-id <messages>]
  (POST (str "/api/jobs/" job-id "?action=" action)
        {:params {:agent {:hostname       "?"
                          :system_name    "toshtogo-ui"
                          :system_version "?"}}
         :format :json
         :handler         (fn [response]
                            (put! <messages> [:job-modified {:response response
                                                             :job-id job-id}]))

         :error-handler   (fn [response]
                            (put! <messages> [:failure {:response response}]))}))

(defn actions [{:keys [job_id outcome]} <messages>]
  (println "ouytcome" outcome)
  (case outcome
    :running (dom/button #js {:className "btn btn-danger"
                              :onClick   (fn [_] (act! "pause" job_id <messages>))}
               "Pause")
    :waiting (dom/button #js {:className "btn btn-danger"
                              :onClick   (fn [_] (act! "pause" job_id <messages>))}
               "Pause")
    :cancelled (dom/button #js {:className "btn btn-success"
                                :onClick   (fn [_] (act! "retry" job_id <messages>))}
                 "Retry")
    nil))

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
      (dom/div #js {:className "container"}
        (title job)

        (actions job <messages>)

        (dom/div #js {:className "row"}
          (dom/div #js {:className "col-md-5"}
            (panel "Request"
                   (dom/div #js {:id "job-request"})))
          (dom/div #js {:className "col-md-5"}
            (panel "Response"
                   (dom/div #js {:id "job-result"}))))

        (dom/div #js {:className "row"}
          (dom/div #js {:className "col-md-10"}
            (panel "Full state"
                   (dom/div #js {:id "job-json"}))))))))
