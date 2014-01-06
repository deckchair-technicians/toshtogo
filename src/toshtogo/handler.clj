(ns toshtogo.handler
  (:use compojure.core )
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.util.response :as resp]
            [flatland.useful.map :refer [update]]
            [toshtogo.middleware :refer [wrap-body-hash
                                         wrap-db-transaction
                                         wrap-dependencies
                                         wrap-print-response
                                         wrap-print-request
                                         wrap-retry-on-exceptions]]
            [toshtogo.jobs :refer [put-job! get-job]]
            [toshtogo.util :refer [uuid]])
  (:import [toshtogo.web IdempotentPutException]))

(defn job-redirect [job-id]
  (resp/redirect-after-post (str "/api/jobs/" job-id)))

(defroutes api-routes
  (context "/api" []
    (context "/jobs" {:keys [jobs]}
      (GET "/" [] "some jobs")
      (PUT  "/" {:keys [body  check-idempotent!] :as req}
        (let [body   (update body :id uuid)
              job-id (body :id)]

          (check-idempotent!
           job-id
           #(let [job (put-job! jobs body)]
             (job-redirect job-id))
           #(job-redirect job-id))))

      (GET "/:job-id" [job-id]
        (get-job jobs (uuid job-id))))))


(def app
  (routes
   (-> (handler/api api-routes)
       ;wrap-print-response
       wrap-dependencies

       (wrap-json-body {:keywords? true})
       wrap-body-hash
       wrap-db-transaction
       wrap-json-response)))
