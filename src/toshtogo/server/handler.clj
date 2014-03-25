(ns toshtogo.server.handler
  (:require [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [cheshire.generate :as json-gen]
            [ring.util.response :as resp]
            [flatland.useful.map :refer [update]]


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
            [toshtogo.util.core :refer [uuid ppstr debug parse-datetime]])
  (:import [toshtogo.server.util IdempotentPutException]
           [java.io InputStream]))

(defn job-redirect [job-id]
  (resp/redirect-after-post (str "/api/jobs/" job-id)))

(defn commitment-redirect [commitment-id]
  (resp/redirect-after-post (str "/api/commitments/" commitment-id)))

(defroutes api-routes
  (context "/api" []
    (context "/jobs" {:keys [persistence body check-idempotent!]}
             (GET "/" {params :params}
                  {:body (get-jobs persistence {:page     (Integer/parseInt (:page (debug "PARAMS" params) "1"))
                                        :order-by [:contract_finished :contract_claimed :contract_created :job_created]})})
      (context "/:job-id" [job-id]

        (PUT  "/" []
          (let [job-id (uuid job-id)]
            (check-idempotent!
             :create-job job-id
             #(let [job (new-job! persistence
                                  (body :agent)
                                  (-> body
                                      (assoc :job_id job-id)
                                      (dissoc :agent)))]
               (job-redirect job-id))
             #(job-redirect job-id))))

        (GET "/" []
          (let [job-id (uuid job-id)
                job (get-job persistence job-id)]
            (if job
              {:body job}
              (route/not-found "Unknown job id"))))

        (POST "/" [action]
              (let [job-id (uuid job-id)]
                (case action
                  "pause"
                  {:body (pause-job! persistence job-id (body :agent))}
                  "retry"
                  {:body (new-contract! persistence (contract-req job-id))}
                  )))))

    (context "/commitments" {:keys [persistence body check-idempotent!]}
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
                                      (update :contract_due parse-datetime)))
                  (commitment-redirect commitment-id))
             #(commitment-redirect commitment-id))))

        (POST "/heartbeat" []
          {:body (upsert-heartbeat! persistence (uuid commitment-id))}))

      (GET "/:commitment-id" [commitment-id]
        {:body  (get-contract
                  persistence
                 {:commitment_id (uuid commitment-id)
                  :with-dependencies true})}))))

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