(ns ^:figwheel-always toshtogo.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [chan <! put!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ajax.core :refer [GET]]
            [secretary.core :as secretary :refer-macros [defroute]]
            toastr

            [toshtogo.jobs.core :as jobs]
            [toshtogo.jobs.job :as job]
            [toshtogo.jobs.graph :as graph]

            [toshtogo.util.history :as history]
            [cemerick.url :as url]))

(enable-console-print!)

(def base-search-uri "api/jobs?page=1&page_size=25&order-by=job_created desc")

(defn notify
  [type msg]
  (case type
    :error (toastr/error msg)
    :success (toastr/success msg)))

(defn fetch-jobs
  [<messages> api-url]
  (GET api-url
       {:handler         (fn [response]
                           (put! <messages> [:jobs-fetched {:response response}]))

        :error-handler   (fn [response]
                           (put! <messages> [:failure {:response response}]))

        :keywords?       true

        :response-format :json}))

(defn fetch-job-types
  [<messages>]
  (GET "/api/metadata/job_types"
       {:handler         (fn [response]
                           (put! <messages> [:job-types-fetched {:response response}]))

        :error-handler   (fn [response]
                           (put! <messages> [:failure {:response response}]))

        :keywords?       true

        :response-format :json}))

(defn fetch-job [<messages> job-id]
  (println "FETCHING" (str "/api/jobs/" job-id))
  (GET (str "/api/jobs/" job-id)
       {:handler         (fn [response]
                           (put! <messages> [:job-fetched {:response response}]))

        :error-handler   (fn [response]
                           (put! <messages> [:failure {:response response}]))

        :keywords?       true

        :response-format :json}))

(defn fetch-graph [<messages> graph-id]
  (println "FETCHING" (str "/api/graphs/" graph-id))
  (GET (str "/api/graphs/" graph-id)
       {:handler         (fn [response]
                           (put! <messages> [:graph-fetched {:response response}]))

        :error-handler   (fn [response]
                           (put! <messages> [:failure {:response response}]))

        :keywords?       true

        :response-format :json}))

(defn build-routes
  [data <messages>]

  ; Figwheel-driven development requirement
  (secretary/reset-routes!)

  (secretary/add-route! "/"
    (fn [_]
      (println "HOME")
      (history/navigate (str "/jobs?source=" (url/url-encode base-search-uri)))))

  (secretary/add-route! "/jobs"
    (fn [{{:keys [source]} :query-params}]
      (println "JOBS")
      (om/transact! data #(assoc %
                           :view :jobs
                           :status :loading))
      (fetch-jobs <messages> source)
      (fetch-job-types <messages>)))

  (secretary/add-route! "/graphs/:graph-id/jobs/:job-id"
    (fn [{:keys [graph-id job-id]}]
      (om/transact! data #(assoc %
                           :view :job
                           :status :loading))
      (fetch-job <messages> job-id)
      (fetch-graph <messages> graph-id))))

(defn clj->d3
  [graph-map]
  (let [job-look-up (into {} (map-indexed (fn [i {:keys [job_id]}]
                                            [job_id i])
                                          (:jobs graph-map)))]

    {"nodes" (:jobs graph-map)
     "links" (map
               (fn [{:keys [parent_job_id child_job_id]}]
                 {:source (job-look-up parent_job_id)
                  :target (job-look-up child_job_id)})
               (:links graph-map))}))

(defn app-view
  [{:keys [view] :as data} owner]
  (reify
    om/IInitState
    (init-state [_]
      {:<messages> (chan)})

    om/IWillMount
    (will-mount [_]
      (aset toastr/options "positionClass" "toast-top-right")
      (let [<messages> (om/get-state owner :<messages>)]
        (build-routes data <messages>)
        (history/enable-history!)
        (go-loop []
          (when-let [[event body] (<! <messages>)]
            (case event
              :job-fetched (let [{:keys [response]} body]
                             (om/transact! data #(merge % {:status :done
                                                           :job    (update response :outcome keyword)})))

              :jobs-fetched (let [{:keys [response]} body]
                              (om/transact! data #(merge % {:status :done
                                                            :jobs   (:data response)
                                                            :paging (:paging response)})))

              :graph-fetched (let [{:keys [response]} body]
                               (om/transact! data #(merge % {:status :done
                                                             :graph  (-> response
                                                                         clj->d3)})))

              :job-modified (let [{:keys [job-id]} body]
                              (when (and (= job-id (get-in @data [:job :job_id]))
                                         (= :job (get-in @data [:view])))
                                (fetch-job <messages> job-id)))

              :job-types-fetched (let [{:keys [response]} body]
                                   (om/transact! data #(merge % {:status    :done
                                                                 :job-types response})))
              :failure (do
                         (notify :error "Oh dear")
                         (om/transact! data #(assoc % :status :error :error-body (keyword (:error body)))))

              (throw (str "Unknown event: " event))))
          (recur))))

    om/IRenderState
    (render-state [_ {:keys [<messages>]}]
      (dom/div nil
        (dom/nav #js {:className "navbar navbar-default"}
          (dom/div #js {:className "container-fluid"}
            (dom/div #js {:className "navbar-header"}
              (dom/a #js {:className "navbar-brand"
                          :href "/"}
                "Toshtogo"))))
        (dom/div #js {:className "row"}
          (dom/div #js {:className "col-xs-1"})
          (dom/div #js {:className "col-xs-10"}
            (println "VIEW" view)
            (case view
              :jobs
              (om/build jobs/jobs-view data {:opts {:base-search-uri base-search-uri}})

              :job
              (dom/div nil
                (om/build graph/graph-view (:graph data) {:init-state {:<messages> <messages>}})
                (om/build job/job-view (:job data) {:init-state {:<messages> <messages>}}))

              (dom/div nil "You have found a broken link, congratulations!"))))))))

(defonce app-state
  (atom {:search {:page-size 25}}))

(om/root
  app-view
  app-state
  {:target (. js/document (getElementById "app"))})

; This seems to be required to make Figwheel not act like a crying baby
(defn on-js-reload
  []
  )
