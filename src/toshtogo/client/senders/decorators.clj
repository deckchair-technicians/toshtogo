(ns toshtogo.client.senders.decorators
  (:import (toshtogo.client.senders SenderException))
  (:require [flatland.useful.map :refer [update]]
            [toshtogo.util.core :refer [debug exponential-backoff retry-until-success]]
            [toshtogo.util.json :as json]
            [toshtogo.client.util :refer [nil-on-404 throw-500]]
            [toshtogo.client.senders.protocol :refer :all]))

(defn wrap-follow-redirect
  ([decorated]
   (wrap-follow-redirect decorated
                    (fn [sender resp] [sender resp]
                      ; See code in handlers. Because CORS requires a 200 response,
                      ; we have to represent redirects slightly disgustingly.
                      (if (or (and (= 200 (:status resp))
                                   (get-in resp [:headers "Location"]))
                              (= 303 (:status resp)))
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

(defn wrap-json-decode [decorated]
  (reify
    Sender
    (POST! [this location message]
      (json/decode (:body (POST! decorated location message))))

    (PUT! [this location message]
      (json/decode (:body (PUT! decorated location message))))

    (GET [this location]
      (json/decode (:body (GET decorated location))))))

(defn wrap-debug [decorated should-debug]
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

(defn wrap-nil-404
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

(defn wrap-retry-sender [decorated opts]
  (if (= false (:should-retry opts))
    decorated
    (let [retry-opts (-> opts
                         (update :exponential-backoff #(or % 5000))
                         (update :error-fn #(or % (constantly nil))))]
      (reify Sender
        (POST! [this location message]
          (retry-until-success retry-opts (POST! decorated location message)))
        (PUT! [this location message]
          (retry-until-success retry-opts (PUT! decorated location message)))
        (GET [this location]
          (retry-until-success retry-opts (GET decorated location)))))))

(defn wrap-decoration [sender & {:keys [should-retry error-fn timeout debug] :or {debug false} :as opts}]
  (-> sender
      wrap-throw-500
      (wrap-retry-sender opts)
      wrap-follow-redirect
      wrap-nil-404
      wrap-json-decode
      (wrap-debug debug)))
