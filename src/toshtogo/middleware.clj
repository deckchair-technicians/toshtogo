(ns toshtogo.middleware
  (:require [clojure.java.jdbc :as sql]
            [toshtogo.config :refer [db]]
            [net.cgrand.enlive-html :as html]
            [toshtogo.agents  :refer [sql-agents]]
            [toshtogo.contracts :refer [sql-contracts]]
            [toshtogo.jobs :refer [sql-jobs]]
            [toshtogo.web.idempotentput :refer [check-idempotent!]]
            [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.pprint :refer [pprint]]
            [toshtogo.util :refer [murmur! ppstr byte-array-input! byte-array-output!]])
  (:import [java.io ByteArrayInputStream]))


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
  [handler]
  (fn [req]
    (sql/db-transaction [cnxn db]
      (handler (assoc req :cnxn cnxn)))))

(defn wrap-dependencies
  "Adds protocol implementations for services"
  [handler]
  (fn [req]
    (let [cnxn              (req :cnxn)
          body-hash         (req :body-hash)
          check-idempotent* (partial check-idempotent! cnxn body-hash)
          agents            (sql-agents cnxn)
          contracts         (sql-contracts cnxn)
          jobs              (sql-jobs cnxn agents contracts)]
      (handler (assoc req
          :check-idempotent! check-idempotent*
          :agents agents
          :contracts contracts
          :jobs jobs)))))

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

(defn wrap-print-response [handler]
  (fn [req] (let [resp (handler req)]
             (pprint resp)
             resp)))

(defn wrap-print-request
  [handler message]
  (fn [req]
    (println)
    (println message)
    (println "---------------------------")
    (pprint req)
    (handler req)))
