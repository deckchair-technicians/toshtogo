(ns toshtogo.server.util.middleware
  (:require [clojure.java.jdbc :as sql]
            [net.cgrand.enlive-html :as html]
            [schema.core :as sch]
            [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.pprint :refer [pprint]]
            [ring.middleware.json :as ring-json]
            [flatland.useful.map :refer [update]]
            [toshtogo.server.util.idempotentput :refer [check-idempotent!]]
            [toshtogo.server.persistence.sql :refer [sql-persistence]]
            [toshtogo.server.api :refer [api]]
            [toshtogo.server.logging :refer [error-event safe-log]]
            [toshtogo.server.validation :refer [validated Agent]]

            [toshtogo.util.core :refer [debug ppstr cause-trace exception-as-map]]
            [toshtogo.util.json :as json]
            [toshtogo.util.hashing :refer [murmur!]]
            [toshtogo.util.io :refer [byte-array-input! byte-array-output!]])
  (:import [java.io ByteArrayInputStream]
           (clojure.lang ExceptionInfo)
           (toshtogo.server.logging ValidatingLogger DeferredLogger)))


(defn wrap-body-hash
  [handler]
  (fn [req]
    (if-let [body (req :body)]
      (let [^ByteArrayInputStream
            body (byte-array-input! body)
            hash (murmur! body)]
        (.reset body)
        (handler (assoc req :body-hash hash :body body)))
      (handler req))))


(defn wrap-db-transaction
  "Wraps the handler in a db transaction and adds the connection to the
    request map under the :cnxn key."
  [handler db]
  (fn [req]
    (sql/with-db-transaction [cnxn db]
                             (handler (assoc req :cnxn cnxn)))))

(defn sql-deps
  [cnxn logger agent-details]
  (let [persistence (sql-persistence cnxn)]
    {:persistence persistence
     :agent       (validated agent-details (sch/maybe Agent))
     :api         (api persistence logger agent-details)}))

(defn wrap-dependencies
  "Adds protocol implementations for services"
  [handler logger]
  (fn [req]
    (let [cnxn (req :cnxn)
          body-hash (req :body-hash)
          check-idempotent* (partial check-idempotent! cnxn body-hash)]
      (handler (-> req
                   (assoc :check-idempotent! check-idempotent*)
                   (merge (sql-deps cnxn logger (get-in req [:body :agent])))
                   (update :body #(dissoc % :agent)))))))

(defn- retry*
  ([retries-left exception-types f]
   (try
     (f)
     (catch Throwable e
       (if (and (not= retries-left 0)
                (some #(instance? % e) exception-types))
         (retry* (dec retries-left) exception-types f)
         (throw e))))))

(defn wrap-retry-on-exceptions
  [handler & exception-types]
  (let [exception-types (set exception-types)]
    (fn [req]
      (retry* 3 exception-types #(handler req))
      )))

(defn wrap-print-response
  [handler & messages]
  (fn [req]
    (let [resp (handler req)]
      (println)
      (println "Response")
      (when messages (println messages))
      (println "---------------------------")
      (pprint resp)
      resp)))

(defn wrap-print-request
  [handler & messages]
  (fn [req]
    (println)
    (println "Request")
    (when messages (println messages))
    (println "---------------------------")
    (pprint req)
    (handler req)))

(defn json-response [resp]
  (ring.util.response/content-type resp "application/json; charset=utf-8"))

(defn wrap-json-response
  "Identical to ring.middleware.json/wrap-json-response but using our json encoder,
  to ensure consistency."
  [handler]
  (fn [request]
    (let [response (handler request)]
      (if (coll? (:body response))
        (-> response
            json-response
            (update-in [:body] json/encode))
        response))))

(defn wrap-json-body
  "Like ring.middleware.json/wrap-json-body, but always tries to
  parse the request body"
  [handler]
  (fn [request]
    (handler (update request :body json/decode))))

(defn wrap-logging
  "Adds a DeferredLogger to request as :logger.

  If request succeeds, sends contents of DeferredLogger to logger.

  Rethrows exceptions in wrapped handler, after logging an error event. Event map contains :events_before_error-
  the log events before the error happened, which will not be logged independently. This is useful to diagnose
  what the server was doing just before an exception occured.

  Catches exceptions with logging and prints cause trace, so log failures don't bring down the application."
  [handler logger]
  (fn [req]
    (let [log-events (atom [])
          deferred-logger (ValidatingLogger. (DeferredLogger. log-events))]
      (try
        (let [resp (handler (assoc req :logger deferred-logger))]
          (apply safe-log logger @log-events)
          resp)
        (catch Exception e
          (safe-log logger (error-event e @log-events))
          (throw e))))))


(defn exception-response [e status-code]
  (json-response {:body   (json/encode (exception-as-map e))
                  :status status-code}))

(defn wrap-json-exception
  [handler]
  (fn [request]
    (try (handler request)
         (catch Throwable e
           (if (= :bad-request (-> e ex-data :cause))
             (exception-response e 400)
             (exception-response e 500))))))

(defn wrap-if [handler pred middleware & args]
  (if pred
    (apply middleware handler args)
    handler))

(defn wrap-cors
      [handler]
  (fn [req]
    (when-let [resp (handler req)]
      (update-in
        resp
        [:headers]
        assoc
        "Access-Control-Allow-Origin" "*"
        "Access-Control-Allow-Methods" "POST,GET,OPTIONS,PUT,DELETE"
        "Access-Control-Allow-Headers" (get-in req [:headers "access-control-request-headers"] "*")))))
