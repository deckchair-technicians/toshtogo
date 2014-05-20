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

            [toshtogo.util.core :refer [uuid uuid-str debug cause-trace]]
            [toshtogo.server.migrations.run :refer [run-migrations!]]))

(def migrated-dev-db (delay (run-migrations! dev-db)))

(def in-process {:type :app :app (dev-app :debug false)})
(def localhost {:type :http :base-url "http://localhost:3000"})

(def client-config in-process)
(def client (ttc/client client-config
                        :error-fn (fn [e] (pprint (cause-trace e)))
                        :debug false
                        :timeout 1000
                        :system "client-test"
                        :version "0.0"))
(def no-retry-client (ttc/client client-config
                        :should-retry false
                        :debug false
                        :system "client-test"
                        :version "0.0"))

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