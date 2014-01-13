(ns toshtogo.client.senders
  (:require [ring.mock.request :refer [request body header]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [toshtogo.util.core :refer [debug]]
            [toshtogo.agents :refer [get-agent-details]]))

(defn hostname
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
  (PUT! [ this location message])
  (GET [ this location]))

(defn FollowingSender [sender follow]
  (reify
    Sender
    (PUT! [this location message]
      (follow this (PUT! sender location message)))

    (GET [this location]
        (follow this (GET sender location)))))

(defn JsonSender [sender]
  (reify
    Sender
    (PUT! [this location message]
      (json/parse-string (:body (PUT! sender location message)) keyword))
    (GET [this location]
        (json/parse-string (:body (GET sender location)) keyword))))

(defn AppSender [agent-details app]
  (reify Sender
    (PUT! [this location message]
      (let [req (request
                 :put
                 (str "http://toshtogo.test/" (str/replace-first location #"^/" "")))]
        (app (-> req
                 (body (json/encode (assoc  message :agent agent-details)))
                 (assoc :content-type "application/json")))))

    (GET [this location]
        (app (request :get (str "http://toshtogo.test/" (str/replace-first location #"^/" "")))))))

(defn DebugSender [sender]
  (reify Sender
    (PUT! [this location message]
      (debug "PUT RESPONSE" (apply PUT! sender (debug "PUT!" [location message]))))
    (GET [this location]
        (debug "GET" (GET sender location)))))

(defn app-sender
  ([app]
     (app-sender app "unknown" "unknown"))
  ([app system version]
     (DebugSender
      (JsonSender
       (FollowingSender
        (AppSender (get-agent-details system version) app)
        (fn [sender resp] [sender resp]
          (if (= 303 (:status resp))
            (GET sender (get-in resp [:headers "Location"] ))
            resp)))))))
