(ns toshtogo.handler
  (:use compojure.core)
  (:require [compojure.handler :as handler]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.util.response :as resp]
            [toshtogo.middleware :refer [wrap-db-transaction
                                         wrap-dependencies
                                         wrap-print-response]]
            [toshtogo.jobs :refer [new-job! get-job]]
            [toshtogo.util :refer [uuid]]))

(defroutes api-routes
  (GET "/hello" [] "hello")
  (context "/api" []
           (GET "/" [] "api")
           (context "/jobs" {:keys [jobs]}
                    (GET "/" [] "some jobs")
                    (POST "/" {:keys [body] :as req}
                          (let [job (new-job! jobs body)]
                            (resp/redirect-after-post (str "/api/jobs/" (:job_id  job)))))

                    (GET "/:job-id" [job-id]
                         (get-job jobs (uuid job-id))))))


(def app
  (routes
   (-> (handler/api api-routes)
       ;wrap-print-response
       wrap-dependencies

       (wrap-json-body {:keywords? true})
       wrap-db-transaction
       wrap-json-response)))
