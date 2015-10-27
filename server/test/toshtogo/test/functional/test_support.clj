(ns toshtogo.test.functional.test-support
  (:require [toshtogo.client
             [core :as ttc]
             [protocol :refer :all]]

            [toshtogo.server
             [core :refer [dev-app dev-db]]]

            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]

            [clojure
             [pprint :refer [pprint]]]

            [schema.core :as sch]

            [toshtogo.server.persistence.sql-persistence :refer [sql-persistence]]
            [toshtogo.server.api :refer [api]]
            [toshtogo.util.core :refer [uuid uuid-str debug cause-trace]]
            [toshtogo.client.util :as client-util]
            [toshtogo.server.migrations.run :refer [run-migrations!]]
            [clojure.java.jdbc :as jdbc])

  (:import [java.net ServerSocket]))

(defn available-port []
  (let [socket (ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(def migrated-dev-db (delay (run-migrations! dev-db)))

(defn reset-database!
  [db]
  (jdbc/db-do-commands db
                       "drop schema if exists public cascade"
                       "create schema public")
  (run-migrations! db))

(defn reset-dev-db [] (reset-database! dev-db))

(def in-process {:type :app :app (dev-app :debug false)})
(def localhost {:type :http :base-url "http://localhost:3000"})

(def client-config in-process)

(defn test-client [& {:as opts}]
  (sch/set-fn-validation! true)
  (apply ttc/client
         (or (:client-config opts) client-config)
         (->> opts
             (merge {:error-fn (fn [e]
                                 (println)
                                 (println "Client threw exception")
                                 (pprint (cause-trace e)))
                     :debug    false
                     :timeout  1000
                     :system   "tests"
                     :version  "0.0"
                     :should-retry true})
             (mapcat identity))))

(def client (test-client))

(def no-retry-client (test-client :should-retry false))

(defn return-success [job] (success {:result 1}))

(defn return-success-with-result [result]
  (fn [job] (success result)))

(defn echo-request [job] (success (:request_body job)))

(defn return-error [job] (error "something went wrong"))

(defn get-and-select [client query & keys]
  (->> (get-jobs client query)
       (:data)
       (map #(select-keys % keys))))

(defn ids-and-created-dates [client query]
  (get-and-select client query :job_id  :job_created :job_type))

(defn isinstance [c]
  (fn [x] (instance? c x)))

(def agent-details (client-util/agent-details "savagematt" "toshtogo"))

(defn deps [cnxn]
  (let [persistence (sql-persistence cnxn)]
    {:persistence persistence
     :api         (api persistence agent-details)}))
