(ns toshtogo.test.functional.test-support
  (:require [toshtogo.client.protocol :refer :all]
            [toshtogo.server.core :refer [dev-app dev-db]]
            [toshtogo.client.core :as ttc]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [clojure.pprint :refer [pprint]]

            [clojure.stacktrace :refer [print-cause-trace]]
            [schema.core :as sch]
            [schema.coerce :as coer]
            [schema.utils :as s-util]
            [schema.macros :as s-macros]
            [midje.checking.core :refer [as-data-laden-falsehood]]

            [toshtogo.server.persistence.sql :refer [sql-persistence]]
            [toshtogo.server.api :refer [api]]
            [toshtogo.util.core :refer [uuid uuid-str debug cause-trace]]
            [toshtogo.client.util :as client-util]
            [toshtogo.server.migrations.run :refer [run-migrations!]]))

(def migrated-dev-db (delay (run-migrations! dev-db)))

(def in-process {:type :app :app (dev-app :debug false)})
(def localhost {:type :http :base-url "http://localhost:3000"})

(def client-config in-process)

(defn test-client [& {:as opts}]
  (apply ttc/client
         (or (:client-config opts) client-config)
         (->> opts
             (merge {:error-fn (fn [e] (pprint (cause-trace e)))
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
     :api         (api persistence nil agent-details)}))