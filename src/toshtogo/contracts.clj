(ns toshtogo.contracts
  (:require [toshtogo.sql :as tsql]
            [clj-time.core :refer [now]]
            [toshtogo.util :refer [uuid]]))

(defprotocol Contracts
  (new-contract! [this job-id]))

(defn contract-record [job-id]
  {:contract_id (uuid) :job_id job-id :created (now)})

(deftype SqlContracts [cnxn]
  Contracts
  (new-contract! [this job-id]
    (tsql/insert! cnxn :contracts (contract-record job-id))))

(defn sql-contracts [cnxn]
  (SqlContracts. cnxn))
