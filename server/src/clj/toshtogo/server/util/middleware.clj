(ns toshtogo.server.util.middleware
  (:require [clojure.java.jdbc :as sql]

            [schema.core :as sch]

            [clojure
             [pprint :refer [pprint]]
             [string :refer [upper-case]]
             [stacktrace :refer [print-cause-trace]]]

            [ring.middleware.json :as ring-json]
            [flatland.useful.map :as mp]

            [toshtogo.server.persistence
             [logging-persistence :refer [logging-persistence]]
             [sql-persistence :refer [sql-persistence]]]

            [toshtogo.server.util
             [idempotentput :refer [check-idempotent!]]
             [sql :refer [with-exception-conversion execute!]]]

            [toshtogo.server
             [api :refer [api]]
             [validation :refer [matches-schema? validated]]
             [logging :refer [error-event safe-log]]]

            [toshtogo.util
             [core :refer [debug ppstr cause-trace exception-as-map with-sys-out]]
             [io :refer [byte-array-input! byte-array-output!]]
             [hashing :refer [murmur!]]
             [json :as json]]

            [toshtogo
             [schemas :refer [Agent]]]
            )
  (:import [java.io ByteArrayInputStream]
           [toshtogo.server.logging ValidatingLogger DeferredLogger]))


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
    (with-exception-conversion
      (sql/with-db-transaction [cnxn db]
                               (handler (assoc req :cnxn cnxn))))))

(defn sql-deps
  [cnxn logger agent-details]
  (let [persistence (-> (sql-persistence cnxn)
                        (logging-persistence logger))]
    {:persistence persistence
     :agent       (validated agent-details (sch/maybe Agent))
     :api         (api persistence agent-details)}))

(defn wrap-dependencies
  "Adds protocol implementations for services"
  [handler]
  (fn [req]
    (let [cnxn (req :cnxn)
          body-hash (req :body-hash)
          check-idempotent* (partial check-idempotent! cnxn body-hash)]
      (handler (-> req
                   (assoc :check-idempotent! check-idempotent*)
                   (merge (sql-deps cnxn (:logger req) (get-in req [:body :agent])))
                   (mp/update :body #(dissoc % :agent)))))))

(defn- retry*
  ([retries-left exception-schemas f]
   (try
     (f)
     (catch Throwable e
       (if (and (not= retries-left 0)
                (some #(matches-schema? % (ex-data e)) exception-schemas))
         (retry* (dec retries-left) exception-schemas f)
         (throw e))))))

(defn wrap-retry-on-exceptions
  [handler retry-count & exception-schemas]
  (fn [req]
    (retry* retry-count exception-schemas (fn [] (handler req)))))

(defn request-summary [req]
  (str (upper-case (name (:request-method req))) " " (:uri req)))

(defn wrap-print-response
  [handler & messages]
  (fn [req]
    (let [resp (handler req)]
      (with-sys-out
        (println)
        (println (str "Response [" (request-summary req) "]"))
        (when messages (println messages))
        (println "---------------------------")
        (pprint resp))

      resp)))

(defn wrap-print-request
  [handler & messages]
  (fn [req]
    (with-sys-out
      (println)
      (println (str "Request [" (request-summary req) "]"))
      (when messages (println messages))
      (println "---------------------------")
      (pprint req))

    (handler req)))

(defn wrap-instrumentation
  [instrumentation-atom handler]
  (fn [req]
    (let [start-time (System/currentTimeMillis)
          response (handler req)
          time-taken (- (System/currentTimeMillis) start-time)]
      (swap! instrumentation-atom conj {:request req :response response :time-taken time-taken})
      response)))

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
    (handler (mp/update request :body json/decode))))

(defn should-log? [request]
  (let [{:keys [request-method uri]} request]
    (and (not= :get request-method)
         (> 0 (.indexOf uri "heartbeat")))))

(defn log-request [handler]
  (fn [request]
    (when (should-log? request)
      (safe-log (:logger request) [{:event_type :request
                                    :event_data (:body request)}]))
    (handler request)))

(defn wrap-logging-transaction
  "Adds a DeferredLogger to request as :logger, which collects log events in an atom.

  If request succeeds, sends contents of DeferredLogger to a logger produced by logger-factory.

  If the handler throws an exception, all log events up to that point will be logged in a
  single :server_error logging event  in :events_before_error. This is useful to diagnose
  what the server was doing just before an exception occured.

  If the logging itself throws an exception, prints cause trace, so log failures don't bring
  down the application."
  [handler logger-factory]
  (assert (fn? logger-factory) "Logger factory should be a function")

  (fn [req]
    (let [last-logged-exception (or (:last-logged-exception req) (atom nil))
          logger (ValidatingLogger. (logger-factory))
          log-events (atom [])
          deferred-logger (ValidatingLogger. (DeferredLogger. log-events))]
      (try
        ; Attach a deferred logger to the request that will buffer events for
        ; Logging later. Then call the next handler
        (let [resp (handler (assoc req
                              :last-logged-exception last-logged-exception
                              :logger deferred-logger
                              :log-events log-events))]
          ; If the handler succeeds, log the events from the deferred logger
          ; to a real logger created by logger-factory
          (safe-log logger @log-events)
          resp)
        (catch Throwable e
               ; If handler throws an exception, log the exception.
               ;
               ; We check whether another wrap-logging-transaction further down the stack
               ; has already logged the exception, so we can nest wrap-logging-transaction
               ; without worrying about double-logging
               ;
               ; The exception event includes the log events buffered by the deferred logger
               ; (which we attached to the request above). These events have presumably been
               ; rolled back now, but it's useful to see on the exception event what we were
               ; doing before the exception occured.
               ;
               (when (not= e @last-logged-exception)
                 (reset! last-logged-exception e)
                 (safe-log logger [(error-event e @log-events req)]))
               (throw e))))))

(defn wrap-clear-logs-before-handling [handler]
  (fn [req]
    (reset! (:log-events req) nil)
    (handler req)))

(defn exception-response [e status-code]
  (json-response {:body   (json/encode (exception-as-map e))
                  :status status-code}))

(defn wrap-json-exception
  [handler]
  (fn [request]
    (try (handler request)
         (catch Throwable e
           (case (-> e ex-data :cause)
             :bad-request
             (exception-response e 400)

             :database-unavailable
             (exception-response e 503)

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
