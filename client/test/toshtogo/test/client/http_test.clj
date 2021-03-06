(ns toshtogo.test.client.http-test
  (:require [midje.sweet :refer :all]
            [vice.midje :refer [matches]]
            [org.httpkit.fake :refer [with-fake-http]]
            [toshtogo.util.json :as json]
            [toshtogo.client
             [protocol :refer [wrap-exception-handling]]
             [http :refer :all]]))

(fact "wrap-http-request"
  (let [wrapped-handler (-> identity
                            (wrap-http-request))]

    (fact "decodes json responses"
      (with-fake-http [{:url    "http://somewhere.localhost"
                        :method :get}
                       {:status  200
                        :headers {"Content-Type" "application/json; "}
                        :body    (json/encode {:a 123 :camelCase 345})}]

        (wrapped-handler {:url    "http://somewhere.localhost"
                          :method :get})
        => (matches {:outcome :success
                     :result  {:response {:body {:a          123
                                                 :camel_case 345}}}})))

    (fact "encodes request body as json if it is a map"
      (let [request-received (atom nil)]
        (with-fake-http [{:url    "http://somewhere.localhost"
                          :method :post
                          :body   (json/encode {:a 123})}
                         (fn [_ request _]
                           (reset! request-received request)
                           (delay {:status 200}))]

          (wrapped-handler {:url    "http://somewhere.localhost"
                            :method :post
                            :body   {:a 123}})
          @request-received => (contains {:body    (json/encode {:a 123})
                                          :headers (contains {"Content-Type" "application/json"})}))))

    (fact "throws useful exception if response isn't acceptable"
      (with-fake-http ["http://somewhere.localhost"
                       {:status  200
                        :headers {"Content-Type" "application/json;"}
                        :body    (json/encode {:a 123})}]
        (let [handler (-> identity
                          (wrap-http-request :client (http-client :acceptable-response? (constantly false))))]

          (handler {:url    "http://somewhere.localhost"
                    :method :get})
          => (matches {:outcome :error
                       :error   {:response {:body   {:a 123}
                                            :status 200}}}))))))
