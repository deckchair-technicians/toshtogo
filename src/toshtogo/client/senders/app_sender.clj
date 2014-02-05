(ns toshtogo.client.app-sender
  (:require [ring.mock.request :refer [request body header]]
            [clojure.string :as str]
            [toshtogo.client.senders :refer :all]
            [toshtogo.util.json :as tjson]))

(defn AppSender [agent-details app]
  (letfn [(make-request [method location message]
                        (let [req (request
                                    method
                                    (str "http://toshtogo.test/" (str/replace-first location #"^/" "")))]
                          (app (-> req
                                   (body (tjson/encode (assoc message :agent agent-details)))
                                   (assoc :content-type "application/json")))))]
    (reify Sender
      (POST! [this location message]
        (make-request :post location message))

      (PUT! [this location message]
        (make-request :put location message))

      (GET [this location]
        (app (request :get (str "http://toshtogo.test/" (str/replace-first location #"^/" ""))))))))