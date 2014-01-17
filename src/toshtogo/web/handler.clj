(ns toshtogo.web.handler
  (:require [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.util.response :as resp]
            [flatland.useful.map :refer [update]]
            [toshtogo.web.middleware :refer [wrap-body-hash
                                             wrap-db-transaction
                                             wrap-dependencies
                                             wrap-print-response
                                             wrap-print-request
                                             wrap-retry-on-exceptions]]
            [toshtogo.api :refer :all]
            [toshtogo.util.core :refer [uuid ppstr debug parse-datetime]])
  (:import [toshtogo.web IdempotentPutException]
           [java.io InputStream]))

(defn job-redirect [job-id]
  (resp/redirect-after-post (str "/api/jobs/" job-id)))

(defn commitment-redirect [commitment-id]
  (resp/redirect-after-post (str "/api/commitments/" commitment-id)))

(defroutes api-routes
  (context "/api" []
    (context "/jobs" {:keys [api body check-idempotent!]}
      (context "/:job-id" [job-id]

        (PUT  "/" []
          (let [job-id (uuid job-id)]
            (check-idempotent!
             :create-job job-id
             #(let [job (put-job! api (assoc body :job_id job-id))]
                (job-redirect job-id))
             #(job-redirect job-id))))

        (GET "/" []
          (let [job-id (uuid job-id)]
            {:body (get-job api job-id)}))

        (POST "/pause" []
          (let [job-id (uuid job-id)]
            (check-idempotent!
             :pause
             job-id
             (constantly :wtf)
             (constantly :wtf))))))

    (context "/commitments" {:keys [api body check-idempotent!]}
      (PUT "/" []
        (let [commitment-id (uuid (body :commitment_id))]
          (check-idempotent!
           :create-commitment commitment-id
           #(if-let [commitment (request-work! api commitment-id (body :tags) (body :agent))]
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
                                      (update :contract_due parse-datetime)))
                  (commitment-redirect commitment-id))
             #(commitment-redirect commitment-id))))

        (POST "/heartbeat" []
          (heartbeat! api commitment-id)))

      (GET "/:commitment-id" [commitment-id]
        {:body  (get-contract
                 api
                 {:commitment_id (uuid commitment-id)
                  :return-jobs true
                  :with-dependencies true})}))))

  (route/not-found {:status "I'm sorry :("})


(def app
  (routes
   (-> (handler/api api-routes)
       ;wrap-print-response
       wrap-dependencies

       (wrap-json-body {:keywords? true})
       wrap-body-hash
       wrap-db-transaction
       wrap-json-response)))
