(ns toshtogo.client.senders.decorators
  (:require [toshtogo.util
             [core :refer [debug exponential-backoff retry-until-success]]
             [json :as json]]

            [toshtogo.client
             [util :refer [nil-on-404 throw-500 throw-400]]
             [protocol :refer :all]]

            [toshtogo.client.senders
             [protocol :refer [Sender POST! GET PUT!]]])

  (:import [java.net ConnectException]))

(defn wrapper [decorated map-fn]
  (reify
    Sender
    (POST! [this location message]
      (map-fn this (POST! decorated location message)))

    (PUT! [this location message]
      (map-fn this (PUT! decorated location message)))

    (GET [this location]
      (map-fn this (GET decorated location)))))

(defn wrap-follow-redirect
  ([decorated]
   (wrap-follow-redirect decorated
                    (fn [sender resp] [sender resp]
                      ; See code in handlers. Because CORS requires a 200 response,
                      ; we have to represent redirects slightly disgustingly.
                      (if (or (and (= 200 (:status resp))
                                   (get-in resp [:body :location]))
                              (= 303 (:status resp)))
                        (GET sender (get-in resp [:body :location]))
                        resp))))
  ([decorated follow]
   (wrapper decorated follow)))

(defn wrap-json-decode-body
  [decorated]
  (wrapper decorated (fn [sender resp] (update resp :body json/decode))))

(defn wrap-extract-body
  [decorated]
  (wrapper decorated (fn [sender resp] (:body resp))))

(defn wrap-debug
  [decorated should-debug]
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
  (wrapper decorated (fn [sender resp] (nil-on-404 resp))))

(defn wrap-throw-500
  [decorated]
  (wrapper decorated (fn [sender resp] (throw-500 resp))))

(defn wrap-throw-400
  [decorated]
  (wrapper decorated (fn [sender resp] (throw-400 resp))))

(defn wrap-throw-recoverable-exception-on-connect-exception
  [decorated]
  (wrapper decorated (fn [sender resp]
                       (if (instance? ConnectException (:error resp))
                         (throw (ex-info (str "HTTP connection failure" (.getMessage (:error resp)))
                                         {:recoverable? true}))
                         resp))))

(defn wrap-throw-recoverable-exception-on-503
  [decorated]
  (wrapper decorated (fn [sender resp]
                       (if (= 503 (:status resp))
                         (throw (ex-info (str resp) {:recoverable? true}))
                         resp))))

(defn immediately-throw
  "Calls f (presumably for logging), then throws the exception
   if it passes the predicate"
  [f immediately-throw?]
  (fn [e]
    (when f
      (f e))
    (when (immediately-throw? e)
      (throw e))))

(defn wrap-retry-sender
  [decorated opts]
  (if-not (:should-retry opts)
    decorated

    (let [retry-opts (-> opts
                         (update :exponential-backoff #(or % 5000))
                         (update :error-fn #(immediately-throw % (fn [e] (not (:recoverable? (ex-data e)))))))]
      (reify Sender
        (POST! [this location message]
          (retry-until-success retry-opts (POST! decorated location message)))
        (PUT! [this location message]
          (retry-until-success retry-opts (PUT! decorated location message)))
        (GET [this location]
          (retry-until-success retry-opts (GET decorated location)))))))

(defn wrap-decoration [sender & {:keys [should-retry error-fn timeout debug] :or {debug false} :as opts}]
  (-> sender
      wrap-throw-recoverable-exception-on-503
      wrap-throw-500
      wrap-throw-400
      wrap-throw-recoverable-exception-on-connect-exception
      (wrap-retry-sender opts)
      wrap-nil-404
      wrap-json-decode-body
      wrap-follow-redirect
      wrap-extract-body
      (wrap-debug debug)))
