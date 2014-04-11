(ns toshtogo.test.functional.test-support
  (:require [toshtogo.client.protocol :refer :all]
            [toshtogo.server.core :refer [dev-app dev-db]]
            [toshtogo.client.core :as ttc]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [clojure.pprint :refer [pprint]]
            [toshtogo.util.core :refer [uuid uuid-str debug cause-trace]]
            [toshtogo.server.migrations.run :refer [run-migrations!]]))

(def migrated-dev-db (delay (run-migrations! dev-db)))

(def in-process {:type :app :app (dev-app :debug false)})
(def localhost {:type :http :base-path "http://localhost:3000"})

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

(def timestamp-tolerance (case (client-config :type)
                           :app (millis 1)
                           :http (seconds 5)))

(defn return-success [job] (success {:result 1}))

(defn get-and-select [client query & keys]
  (->> (get-jobs client query)
       (:data)
       (map #(select-keys % keys))))

(defn ids-and-created-dates [client query]
  (get-and-select client query :job_id  :job_created :job_type))

(defn isinstance [c]
  (fn [x] (instance? c x)))

(defn close-to
      ([expected]
       (close-to expected timestamp-tolerance))
  ([expected tolerance-period]
   (let [acceptable-interval (interval (minus expected tolerance-period)
                                       (plus expected tolerance-period))]
     (fn [x] (within? acceptable-interval expected)))))
