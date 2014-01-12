(ns toshtogo.contracts
  (:require [toshtogo.agents :refer [agent!]]
            [toshtogo.sql :as tsql])

  (:import [java.lang IllegalStateException]))

(defn success [response-body]
  {:outcome :success
   :result  response-body})

(defn error [error-text]
  {:outcome :error
   :error   error-text})

(defprotocol Contracts
  (get-contracts [this params])
  (get-contract  [this params])
  (new-contract! [this job-id])
  (request-work! [this commitment-id tags agent])
  (complete-work! [this commitment-id result]))
