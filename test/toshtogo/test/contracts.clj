(ns toshtogo.test.contracts
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [toshtogo.util :refer [uuid uuid-str debug]]
            [toshtogo.config :refer [dbs]]
            [toshtogo.agents :refer :all]
            [toshtogo.contracts :refer :all]
            [toshtogo.jobs :refer :all]))

(fact "Gets contracts by filters "
  (sql/db-transaction
   [cnxn (dbs :dev)]
   (let [id-one    (uuid)
         id-two    (uuid)
         tag-one   (uuid-str) ;so we can run against a dirty database
         tag-two   (uuid-str)
         agents    (sql-agents cnxn)
         contracts (sql-contracts cnxn agents)
         jobs      (sql-jobs cnxn agents contracts)]

     (put-job! jobs (job id-one (get-agent-details "test" "0.0.0") {:some-data 123} [tag-one]))
     (put-job! jobs (job id-two  (get-agent-details "test" "0.0.0") {:some-data 456} [tag-two]))

     (get-contracts contracts {:state :waiting :tags [tag-one]})
     => (contains (contains {:job_id id-one})))))

(future-fact "Two agents requesting work at the same time will not collide")

(future-fact "If there is no work to do, no work is handed out")
