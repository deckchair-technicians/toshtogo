(ns ^:figwheel-always toshtogo.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [chan <! put!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ajax.core :refer [GET]]
            [secretary.core :as secretary :refer-macros [defroute]]
            toastr

            [toshtogo.util.dates :as dates]

            [clojure.string :as s]

            [toshtogo.jobs.core :as jobs]
            [toshtogo.jobs.job :as job]
            [toshtogo.jobs.graph :as graph]

            [toshtogo.util.history :as history]
            [cemerick.url :as url]))

(enable-console-print!)

(defn notify
  [type msg]
  (case type
    :error (toastr/error msg)
    :success (toastr/success msg)))

(defn params->api-string
  [{:keys [outcome job-types] :as query-params}]
  (str (s/join "&"
               (map (partial s/join "=")
                    (map (fn [[k v]]
                           [(name k) v])
                         (dissoc query-params :outcome :job-types))))
       (s/join (map (partial str "&outcome=") (s/split outcome ",")))
       (s/join (map (partial str "&job_type=") (s/split job-types ",")))))

(defn fetch-jobs
  [<messages> query-params]
  (GET (str "/api/jobs?" (params->api-string query-params))
       {:handler         (fn [response]
                           (put! <messages> [:jobs-fetched {:response response}]))

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

(defn string->int
  [s]
  (js/parseInt s 10))

(defn build-routes
  [data <messages>]

  ; Figwheel-driven development requirement
  (secretary/reset-routes!)

  (secretary/add-route! "/"
    (fn [_]
      (println "HOME")
      (history/navigate (str "/jobs?" (history/params->query-string {:page 1
                                                                     :page_size 25
                                                                     :order-by "job_created desc"
                                                                     :outcome ["running"
                                                                               "success"
                                                                               "error"
                                                                               "waiting"
                                                                               "cancelled"]})))))

  (secretary/add-route! "/jobs"
    (fn [{:keys [query-params]}]
      (println "JOBS")
      (om/transact! data #(assoc %
                                 :query (-> query-params
                                            (update-in [:outcome] (fn [outcome] (set (s/split outcome ","))))
                                            (update-in [:job-types] (fn [job-types] (set (s/split job-types ","))))
                                            (update-in [:page] string->int))
                                 :view :jobs
                                 :status :loading))
      (fetch-jobs <messages> query-params)))

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
                                                           :job    (-> response
                                                                       (update :outcome keyword))})))

              :jobs-fetched (let [{:keys [response]} body]
                              (om/transact! data #(merge % {:status :done
                                                            :jobs   (map (fn [job]
                                                                           (-> job
                                                                               (update-in [:job_created] dates/string->date)
                                                                               (update-in [:contract_claimed] dates/string->date)
                                                                               (update-in [:contract_finished] dates/string->date)))
                                                                         (:data response))
                                                            :paging (:paging response)})))

              :graph-fetched (let [{:keys [response]} body]
                               (om/transact! data #(merge % {:status :done
                                                             :graph  (-> response
                                                                         clj->d3)})))

              :job-modified (let [{:keys [job-id graph-id]} body]
                              (when (and (= job-id (get-in @data [:job :job_id]))
                                         (= :job (get-in @data [:view])))
                                (fetch-job <messages> job-id)
                                (fetch-graph <messages> graph-id)))


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
              (om/build jobs/jobs-view data)

              :job
              (dom/div nil
                (om/build job/job-view (:job data) {:init-state {:<messages> <messages>}})
                (om/build graph/graph-view (:graph data) {:init-state {:<messages> <messages>}}))

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
