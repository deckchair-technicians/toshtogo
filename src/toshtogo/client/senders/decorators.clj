(ns toshtogo.client.senders.decorators
  (:import (toshtogo.client.senders SenderException))
  (:require [toshtogo.util.core :refer [debug exponential-backoff retry-until-success]]
            [toshtogo.util.json :as json]
            [toshtogo.client.util :refer [nil-on-404 throw-500]]
            [toshtogo.client.senders.protocol :refer :all]))

(defn following-sender
  ([decorated]
   (following-sender decorated
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

(defn json-sender [decorated]
  (reify
    Sender
    (POST! [this location message]
      (json/decode (:body (POST! decorated location message))))

    (PUT! [this location message]
      (json/decode (:body (PUT! decorated location message))))

    (GET [this location]
      (json/decode (:body (GET decorated location))))))

(defn debug-sender [decorated should-debug]
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

(defn retry-sender
  [decorated & {:keys [error-fn timeout]
                :or {error-fn (constantly nil)}}]

  (let [retry-opts {:interval-fn (partial exponential-backoff 5000)
                    :error-fn    error-fn
                    :timeout     timeout}]
    (reify Sender
      (POST! [this location message]
        (retry-until-success retry-opts (POST! decorated location message)))
      (PUT! [this location message]
        (retry-until-success retry-opts (PUT! decorated location message)))
      (GET [this location]
        (retry-until-success retry-opts (GET decorated location))))))

(defn nil-404
  [decorated]
  (reify Sender
    (POST! [this location message]
      (nil-on-404 (POST! decorated location message)))
    (PUT! [this location message]
      (nil-on-404 (PUT! decorated location message)))
    (GET [this location]
      (nil-on-404 (GET decorated location)))))

(defn wrap-throw-500
  [decorated]
  (reify Sender
    (POST! [this location message]
      (throw-500 (POST! decorated location message)))
    (PUT! [this location message]
      (throw-500 (PUT! decorated location message)))
    (GET [this location]
      (throw-500 (GET decorated location)))))

(defn wrap-retry-sender [sender opts]
  (if (= false (:should-retry opts))
    sender
    (apply retry-sender sender (flatten (seq (select-keys opts [:error-fn :timeout]))))))

(defn default-decoration [sender & {:keys [should-retry error-fn timeout debug] :or {debug false} :as opts}]
  (-> sender
      wrap-throw-500
      (wrap-retry-sender opts)
      following-sender
      nil-404
      json-sender
      (debug-sender debug)))
