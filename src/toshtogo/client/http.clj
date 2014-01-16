(ns toshtogo.client.http
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [toshtogo.agents :refer [get-agent-details]]
            [toshtogo.util.json :as tjson]
            [toshtogo.client.senders :refer :all]))



(defn HttpSender [agent-details base-path]
  (letfn [(prepare-request
            [location message]
            [(str base-path (str/replace-first location #"^/" ""))
              {:body (tjson/encode (assoc  message :agent agent-details))
               :headers {"Content-Type" "application/json"}}])]

    (reify Sender
      (POST! [this location message]
        @(apply http/post (prepare-request location message)))

      (PUT! [this location message]
        @(apply http/put (prepare-request location message)))

      (GET [this location]
          @(http/get (str base-path (str/replace-first location #"^/" "")))))))

(defn http-sender
  ([base-path]
     (http-sender base-path "unknown" "unknown"))
  ([base-path system version]
     (DebugSender false
      (JsonSender
       (FollowingSender
        (HttpSender (get-agent-details system version) base-path)
        (fn [sender resp] [sender resp]
          (if (= 303 (:status resp))
            (GET sender (get-in resp [:headers "Location"] ))
            resp)))))))
