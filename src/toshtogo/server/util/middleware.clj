(ns toshtogo.server.util.middleware
  (:require [clojure.java.jdbc :as sql]
            [net.cgrand.enlive-html :as html]
            [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.pprint :refer [pprint]]
            [toshtogo.server.util.idempotentput :refer [check-idempotent!]]
            [toshtogo.server.agents.sql :refer [sql-agents]]
            [toshtogo.server.api.protocol :refer :all]
            [toshtogo.server.api.sql :refer [sql-api]]
            [toshtogo.util.core :refer [debug ppstr]]
            [toshtogo.util.hashing :refer [murmur!]]
            [toshtogo.util.io :refer [byte-array-input! byte-array-output!]])
  (:import [java.io ByteArrayInputStream]))


(defn sql-deps [cnxn]
  (let [agents (sql-agents cnxn)
        api (sql-api cnxn agents)]

    {:agents agents
     :api    api}))

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

(defn wrap-dependencies
  "Adds protocol implementations for services"
  [handler]
  (fn [req]
    (let [cnxn (req :cnxn)
          body-hash (req :body-hash)
          check-idempotent* (partial check-idempotent! cnxn body-hash)]
      (handler (-> req
                   (assoc :check-idempotent! check-idempotent*)
                   (merge (sql-deps cnxn)))))))

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
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (println)
      (println "Response")
      (println "---------------------------")
      (pprint resp)
      resp)))

(defn wrap-print-request
  [handler]
  (fn [req]
    (println)
    (println "Request")
    (println "---------------------------")
    (pprint req)
    (handler req)))
