(ns toshtogo.middleware
  (:require [clojure.java.jdbc :as sql]
            [toshtogo.config :refer [db]]
            [net.cgrand.enlive-html :as html]
            [toshtogo.agents  :refer [sql-agents]]
            [toshtogo.contracts :refer [sql-contracts]]
            [toshtogo.jobs :refer [sql-jobs]]
            [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.pprint :refer [pprint]]))


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
    (let [cnxn      (req :cnxn)
          agents    (sql-agents cnxn)
          contracts (sql-contracts cnxn)
          jobs      (sql-jobs cnxn agents contracts)]
      (handler (assoc req
          :agents agents
          :contracts contracts
          :jobs jobs)))))

(defn wrap-print-response [handler]
  (fn [req] (let [resp (handler req)]
             (pprint resp)
             resp)))
