(ns toshtogo.client.senders
  (:require [ring.mock.request :refer [request body header]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [toshtogo.util.core :refer [debug]]
            [toshtogo.util.json :as tjson]
            [toshtogo.agents :refer [get-agent-details]]))

(defn- hostname
  []
  (try
    (.getHostName (java.net.InetAddress/getLocalHost))
    (catch java.net.UnknownHostException e
      (throw (RuntimeException.
              (str
               "Can't get hostname. POSSIBLE FIX: http://stackoverflow.com/a/16361018. "
               "\nException was:"
               (.getMessage e)))))))


(defprotocol Sender
  (POST! [this location message])
  (PUT! [this location message])
  (GET [this location]))

(defn FollowingSender [sender follow]
  (reify
    Sender
    (POST! [this location message]
      (follow this (POST! sender location message)))

    (PUT! [this location message]
      (follow this (PUT! sender location message)))

    (GET [this location]
        (follow this (GET sender location)))))

(defn JsonSender [sender]
  (reify
    Sender
    (POST! [this location message]
      (json/parse-string (:body (POST! sender location message)) keyword))

    (PUT! [this location message]
      (json/parse-string (:body (PUT! sender location message)) keyword))

    (GET [this location]
        (json/parse-string (:body (GET sender location)) keyword))))


(defn AppSender [agent-details app]
  (letfn [(make-request [method location message]
            (let [req (request
                       method
                       (str "http://toshtogo.test/" (str/replace-first location #"^/" "")))]
              (app (-> req
                       (body (tjson/encode (assoc  message :agent agent-details)))
                       (assoc :content-type "application/json")))))]
    (reify Sender
      (POST! [this location message]
        (make-request :post location message))

      (PUT! [this location message]
        (make-request :put location message))

      (GET [this location]
          (app (request :get (str "http://toshtogo.test/" (str/replace-first location #"^/" ""))))))))



(defn DebugSender [should-debug sender]
  (if should-debug
    (reify Sender
      (POST! [this location message]
        (debug "POST RESPONSE" (apply POST! sender (debug "POST!" [location message]))))
      (PUT! [this location message]
        (debug "PUT RESPONSE" (apply PUT! sender (debug "PUT!" [location message]))))
      (GET [this location]
          (debug "GET" (GET sender location))))
    sender))

(defn app-sender
  ([app]
     (app-sender app "unknown" "unknown"))
  ([app system version]
     (DebugSender false
      (JsonSender
       (FollowingSender
        (AppSender (get-agent-details system version) app)
        (fn [sender resp] [sender resp]
          (if (= 303 (:status resp))
            (GET sender (get-in resp [:headers "Location"] ))
            resp)))))))
