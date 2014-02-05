(ns toshtogo.client.senders
  (:import (toshtogo.client SenderException))
  (:require [ring.mock.request :refer [request body header]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [toshtogo.util.core :refer [debug exponential-backoff]]
            [toshtogo.util.json :as tjson]
            [toshtogo.client.util :refer [until-successful-response]]
            [toshtogo.agents :refer [get-agent-details]]))


(defprotocol Sender
  (POST! [this location message])
  (PUT! [this location message])
  (GET [this location]))

(defn FollowingSender
  ([decorated]
   (FollowingSender decorated
                    (fn [sender resp] [sender resp]
                      (if (= 303 (:status resp))
                        (GET sender (get-in resp [:headers "Location"]))
                        resp))))
  ([decorated follow]
   (reify
     Sender
     (POST! [this location message]
       (follow this (POST! decorated location message)))

     (PUT! [this location message]
       (follow this (PUT! decorated location message)))

     (GET [this location]
       (follow this (GET decorated location))))))

(defn JsonSender [decorated]
  (reify
    Sender
    (POST! [this location message]
      (json/parse-string (:body (POST! decorated location message)) keyword))

    (PUT! [this location message]
      (json/parse-string (:body (PUT! decorated location message)) keyword))

    (GET [this location]
      (json/parse-string (:body (GET decorated location)) keyword))))


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

(defn DebugSender [should-debug decorated]
  (if should-debug
    (reify Sender
      (POST! [this location message]
        (debug "POST RESPONSE" (apply POST! decorated (debug "POST!" [location message]))))
      (PUT! [this location message]
        (debug "PUT RESPONSE" (apply PUT! decorated (debug "PUT!" [location message]))))
      (GET [this location]
        (println "GET " location)
        (debug "GET RESPONSE" (GET decorated location))))
    decorated))

(defn RetrySender
  [decorated]
  (let [backoff-opts {:interval-fn (partial exponential-backoff 5000)}]
    (reify Sender
      (POST! [this location message]
        (until-successful-response backoff-opts (POST! decorated location message)))
      (PUT! [this location message]
        (until-successful-response backoff-opts (PUT! decorated location message)))
      (GET [this location]
        (until-successful-response backoff-opts (GET decorated location))))))

(defn decorate [sender]
  (DebugSender
    false
    (JsonSender
      (FollowingSender
        (RetrySender
          sender)))))

(defn app-sender
  ([app]
   (app-sender app "unknown" "unknown"))
  ([app system version]
   (decorate (AppSender (get-agent-details system version) app))))
