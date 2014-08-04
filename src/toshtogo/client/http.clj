(ns toshtogo.client.http
  (:require [flatland.useful.map :refer [update]]
            [toshtogo.util.json :as json]
            [org.httpkit.client :as http]
            [toshtogo.client.protocol :refer [success]]))

(def is-redirect? #{301 302 303})

(defn extract-location [response]
  (assoc response :location (get-in response [:headers "location"])))

(defn parse-response [response]
  (let [content-type (or (get-in response [:headers :content-type])
                         (get-in response [:headers "Content-Type"]))]
    (if (and content-type (.startsWith content-type "application/json"))
      (update response :body json/decode)
      response)))

(defn http-kit-request [request]
  (http/request request nil))

(defn encode-body [request]
  (if (map? (:body request))
    (-> request
        (update :body json/encode)
        (update :headers #(assoc % "Content-Type" "application/json")))
    request))

(defn http-client
  "Returns a function which sends an http request.

  If request is successful, returns the response

  The function will encode and decode json requestes and responses.

  It will throw a helpful ExceptionInfo on unacceptable responses, including the request and response."
  [& {:keys [acceptable-response? client]
      :or   {acceptable-response? #(>= 399 (:status %) 200)
             client               http-kit-request}}]
  (fn [request]
    (let [response (-> (encode-body request)
                       (client)
                       (deref)
                       (extract-location)
                       (parse-response)
                       (select-keys [:status :body :headers]))]

      (if (acceptable-response? response)
        response
        (throw (ex-info (str "Response status from "
                             (:method request) " " (:url request)
                             " was " (:status response)
                             " body " (with-out-str (clojure.pprint/pprint (:body response))))
                        {:request  (select-keys request [:url :method :body :query-params :form-params])
                         :response response}))))))

(defn wrap-http-request
  "Wraps a toshtogo handler that returns an http request, executing the request

   If request :body is a map, it will be json encoded and content-type will be set.

   If the response status isn't as expected, (>= 399 % 200) by default, throws ExceptionInfo
   with ex-data containing a helpful map of the request and response.

  If the response succeeds, returns a map with request [:url :method :body :query-params :form-params]
  and the full response."
  [handler & {:keys [client]
              :or   {client (http-client)}}]
  (fn [request]
    (let [request (handler request)
          response (client request)]
      (success {:request  (select-keys request [:url :method :body :query-params :form-params])
                :response response}))))
