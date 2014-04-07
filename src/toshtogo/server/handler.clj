(ns toshtogo.server.handler
  (:require [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [clojure.string :as s]
            [cheshire.generate :as json-gen]
            [ring.util.response :as resp]
            [swiss.arrows :refer :all]
            [clojure.pprint :refer [pprint]]
            [flatland.useful.map :refer [update update-each]]


            [toshtogo.server.util.middleware :refer [wrap-body-hash
                                             wrap-db-transaction
                                             wrap-dependencies
                                             wrap-print-response
                                             wrap-print-request
                                             wrap-retry-on-exceptions
                                             wrap-json-response
                                             wrap-json-exception
                                             wrap-json-body
                                             wrap-if]]
            [toshtogo.server.persistence.protocol :refer :all]
            [toshtogo.server.api :refer :all]
            [toshtogo.util.core :refer [uuid ppstr debug parse-datetime ensure-seq]])
  (:import [toshtogo.server.util IdempotentPutException]
           [java.io InputStream]))

(defn job-redirect [job-id]
  (resp/redirect-after-post (str "/api/jobs/" job-id)))

(defn commitment-redirect [commitment-id]
  (resp/redirect-after-post (str "/api/commitments/" commitment-id)))

(defn parse-order-by-expression [order-by]
  (let [[name direction]   (-> order-by
                               s/trim
                               (s/split #"\s+"))]
    [(keyword name) (or (keyword direction) :asc)]))

(defn parse-order-by [order-by]
  (->> order-by
       ensure-seq
       (map parse-order-by-expression)
       (filter (comp not empty?))))

(defn parse-boolean-param [s]
  (and s (not= "false" (s/trim (s/lower-case s)))))

(defn keywords-param [s]
  (when s
    (->> s
         ensure-seq
         (map keyword))))

(defn normalise-search-params [params]
  (-> params
      (update :order-by (fn [x] (or (parse-order-by x) [:job_created :desc])))
      (update :page (fn [s] (Integer/parseInt (or s "1"))))
      (update :page-size (fn [s] (Integer/parseInt (or s "25"))))
      ;(update-each [:latest_contract :has_contract] parse-boolean-param)
      (update-each [:commitment_id :job_id :depends_on_job_id :dependency_of_job_id :fungibility_group] uuid)
      (update-each [:job_type :outcome] #(when % (map keyword (ensure-seq %))))
      (update :tags keywords-param)
      (update :max_due_time parse-datetime)))

(defn normalise-job-req [req]
  (-> req
      (update-each [:job_id :fungibility_group] uuid)
      (update :job_type keyword)
      (update :tags #(map keyword %))
      (update :dependencies #(map normalise-job-req %))))

(defroutes api-routes
  (context "/api" {:keys [persistence body check-idempotent!]}
    (context "/jobs" []
       (GET "/" {params :params}
            (resp/response (get-jobs persistence (normalise-search-params params))))
      (context "/:job-id" [job-id]

        (PUT  "/" []
          (let [job-id (uuid job-id)]
            (check-idempotent!
             :create-job job-id
             #(let [job (new-job! persistence
                                  (body :agent)
                                  (-> body
                                      (assoc :job_id job-id)
                                      (dissoc :agent)
                                      normalise-job-req))]
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
                  (resp/response (pause-job! persistence job-id (body :agent)))
                  "retry"
                  (resp/response (new-contract! persistence (contract-req job-id)))
                  )))))

    (context "/commitments" []
      (PUT "/" []
        (let [commitment-id (uuid (body :commitment_id))]
          (check-idempotent!
           :create-commitment commitment-id
           #(if-let [commitment (request-work! persistence commitment-id {:job_type (keyword (body :job_type))} (body :agent))]
              (commitment-redirect commitment-id)
              {:status 204})
           #(commitment-redirect commitment-id))))

      (context "/:commitment-id" [commitment-id]
        (PUT "/" []
          (let [commitment-id (uuid commitment-id)]
            (check-idempotent!
             :complete-commitment commitment-id
             #(do (complete-work! persistence commitment-id
                                  (-> body
                                      (update :outcome keyword)
                                      (update :contract_due parse-datetime)
                                      (update :dependencies (fn [deps] (map normalise-job-req deps))))
                                  (body :agent))
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
                  (get-job-types persistence)))))

  (route/not-found {:status "I'm sorry :("})

(defn html-resource [path]
  (resp/resource-response path {:root "toshtogo/gui/"}))

(defroutes site-routes
           (route/resources "/" {:root "toshtogo/gui/"})
           (GET "/jobs" [] (html-resource "jobs.html"))
           (GET "/jobs/:job-id" [job-id] (html-resource "job.html")))


(defn app [db & {:keys [debug] :or {debug false}}]
  (routes
    (handler/site site-routes)
    (-> (handler/api api-routes)
        wrap-dependencies

        (wrap-if debug wrap-print-request)
        wrap-json-body
        wrap-body-hash
        (wrap-db-transaction db)
        wrap-json-response
        (wrap-if debug wrap-print-response)
        wrap-json-exception)))