(ns toshtogo.server.handler
  (:require [compojure
             [core :refer :all]
             [handler :as handler]
             [route :as route]]

            [clojure.string :as s]

            [ring.util
             [codec :as codec]
             [response :as resp]]

            [flatland.useful.map :refer [update update-each map-keys]]

            [toshtogo.server.heartbeat
             [core :refer [start-monitoring!]]]

            [toshtogo.util
             [core :refer [uuid  debug parse-datetime ensure-seq]]]

            [toshtogo.server.util
             [middleware :refer :all]]

            [toshtogo.server.persistence
             [protocol :refer :all]]

            [toshtogo.server
             [api :refer :all]
             [validation :refer :all]
             [logging :refer :all]]))

(defn redirect
      "CORS requires 200 responses for PUT and POST. So we
      have to return a 200 response containing the redirect url."
      [url]
      (-> (resp/response {:redirect true :location url})
          (resp/header "Location" url)))

(defn job-redirect [job-id]
  (redirect (str "/api/jobs/" job-id)))

(defn commitment-redirect [commitment-id]
  (redirect (str "/api/commitments/" commitment-id)))

(defn parse-order-by-expression [order-by]
  (let [[name direction]   (-> order-by
                               s/trim
                               (s/split #"\s+"))]
    [(keyword name) (or (keyword direction) :asc)]))

(defn parse-order-by [order-by]
  (when order-by
    (->> order-by
         ensure-seq
         (map parse-order-by-expression)
         (filter (comp not empty?)))))

(defn parse-boolean-param [s]
  (and s (not= "false" (s/trim (s/lower-case s)))))

(defn sequence-of-keywords [s]
  (when s
    (->> s
         ensure-seq
         (map keyword))))

(defn normalise-search-params [params]
  (-> params
      (map-keys keyword)
      (update :order-by (fn [x] (or (parse-order-by x) [:job_created])))
      ;(update-each [:latest_contract :has_contract] parse-boolean-param)
      (update-each [:tree_id :commitment_id :job_id :depends_on_job_id :dependency_of_job_id :fungibility_group_id] uuid)
      (update-each [:job_type :outcome] #(when % (map keyword (ensure-seq %))))
      (update-each [:fields :tags] sequence-of-keywords)
      (update :max_due_time parse-datetime)))

(defn normalise-paging-params
  "Parse (or default) the paging related parameters."
  [params]
  (-> params
      (update :page (fn [s] (when s (Integer/parseInt s))))
      (update :page_size (fn [s] (Integer/parseInt (or s "25"))))))

(defn normalise-job-req [req]
  (-> req
      (update-each [:job_id :fungibility_group_id] uuid)
      (update :job_type keyword)
      (update :contract_due parse-datetime)
      (update :tags #(map keyword %))
      (update :existing_job_dependencies #(map uuid %))
      (update :dependencies #(map normalise-job-req %))))

(defn update-query-param [query-string param-name new-value]
  (codec/form-encode (assoc (codec/form-decode query-string) (name param-name) new-value)))

(defn page-url [uri query-string page-number]
  (str uri "?" (update-query-param query-string :page page-number)))

(defn paginate [{pages :paging :as jobs} {query :query-string uri :uri :as query}]
  (update jobs :paging (fn [paging] (update paging :pages
                                            (fn [page-count]
                                              (for [page (range 1 (inc page-count))]
                                                (page-url uri query page)))))))

(defn job-types [jobs {query :query-string uri :uri :as query}]
  (assoc jobs :job_types "/api/metadata/job_types"))

(defn restify [jobs query]
  (cond-> jobs
          (sequential? jobs) ((fn [jobs] {:data jobs}))
          (:paging jobs)     (paginate query)
          true               (job-types query)))

(defroutes api-routes
  (context "/api" {:keys [persistence api body check-idempotent!]}
    (context "/trees" []
             (GET "/:tree-id" [tree-id]
                  (resp/response (get-tree persistence (uuid tree-id)))))

    (context "/jobs" []
      (GET "/" {params :query-params :as request}
        (let [normalised-params (-> params normalise-search-params normalise-paging-params)]
              (resp/response (restify (get-jobs persistence normalised-params) request))))
      (context "/:job-id" [job-id]

        (PUT  "/" []
          (let [job-id (uuid job-id)]
            (check-idempotent!
             :create-job job-id
             #(do
               (new-root-job! api
                              (-> body
                                  (assoc :job_id job-id)
                                  normalise-job-req
                                  (validated JobRequest)))
               (job-redirect job-id))
             #(job-redirect job-id))))

        (GET "/" []
          (let [job-id (uuid job-id)
                job (get-job persistence job-id)]
            (if job
              (resp/response job)
              (route/not-found "Unknown job id"))))

        (POST "/" [action]
              (let [job-id (uuid job-id)]
                (case action
                  "pause"
                  (resp/response (pause-job! api job-id))
                  "retry"
                  (resp/response (new-contract! api (contract-req job-id)))
                  )))))

    (context "/commitments" []
      (PUT "/" []
        (let [commitment-id (uuid (body :commitment_id))]
          (check-idempotent!
           :create-commitment commitment-id
           #(if-let [commitment (request-work! api
                                               commitment-id
                                               (normalise-search-params (:query body)))]
              (commitment-redirect commitment-id)
              {:status 204})
           #(commitment-redirect commitment-id))))

      (context "/:commitment-id" [commitment-id]
        (PUT "/" []
          (let [commitment-id (uuid commitment-id)]
            (check-idempotent!
             :complete-commitment commitment-id
             #(do (complete-work! api commitment-id
                                  (-> body
                                      (update :outcome keyword)
                                      (update :contract_due parse-datetime)
                                      (update :existing_job_dependencies (fn [dep-job-id] (map uuid dep-job-id)))
                                      (update :dependencies (fn [deps] (map normalise-job-req deps)))
                                      ; Convert error strings into maps
                                      (update :error (fn [e] (if (string? e)
                                                               {:stacktrace e}
                                                               e)))
                                      (validated JobResult)))
                  (commitment-redirect commitment-id))
             #(commitment-redirect commitment-id))))

        (GET "/" []
             {:body  (get-contract
                       persistence
                       {:commitment_id (uuid commitment-id)
                        :with-dependencies true})})

        (POST "/heartbeat" []
          {:body (upsert-heartbeat! persistence (uuid commitment-id))})))

    (context "/metadata" []
             (GET "/job_types" {:keys [persistence]}
               (get-job-types persistence))))

  (OPTIONS ["/:path" :path #".+"] [] {:status 200 :body {:result "You made a pre-flight CORS OPTIONS request. Well done :-)"}})

  (route/not-found {:status "I'm sorry :("}))

(defn html-resource [path]
  (resp/resource-response path {:root "toshtogo/gui/"}))

(defroutes site-routes
  (GET "/health" [] "I'm fine")
  (route/resources "/" {:root "toshtogo/gui/"})
  (GET "/jobs" [] (html-resource "jobs.html"))
  (GET "/jobs/:job-id" [job-id] (html-resource "job.html")))


(defn start-heartbeat-monitor!
  ([toshtogo-client]
   (start-heartbeat-monitor! toshtogo-client 60))
  ([toshtogo-client interval-seconds]
   (start-heartbeat-monitor! toshtogo-client interval-seconds 60))
  ([toshtogo-client interval-seconds max-ttl]
   (start-monitoring! toshtogo-client interval-seconds max-ttl)))

(defn app [db & {:keys [debug logger-factory]
                 :or {debug false
                      logger-factory (constantly nil)}}]
  (routes
    (handler/site site-routes)
    (-> (handler/api api-routes)
        wrap-dependencies
        (wrap-if debug wrap-print-request)
        (wrap-db-transaction db)
        (wrap-clear-logs-before-handling) ; Only log events from the final retry
        (wrap-retry-on-exceptions 3 UniqueConstraintException)
        (wrap-logging-transaction logger-factory) ; Log events on DB commit. On exception, log exception event, including log event that didn't commit
        log-request
        wrap-json-body
        wrap-body-hash
        wrap-json-response
        (wrap-if debug wrap-print-response)
        wrap-cors
        (wrap-logging-transaction logger-factory) ; Log the exception before wrap-json-exception throws it away
        (wrap-json-exception)
        (wrap-logging-transaction logger-factory) ; Log exceptions from wrap-json-exception
        )))
