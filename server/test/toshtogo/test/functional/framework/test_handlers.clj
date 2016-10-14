(ns toshtogo.test.functional.framework.test-handlers
  (:require [toshtogo.client
             [protocol :refer :all]]))

(defn return-success [_job] (success {:result 1}))

(defn return-success-with-result [result]
  (fn [_job] (success result)))

(defn echo-request [job] (success (:request_body job)))

(defn return-error [_job] (error "something went wrong"))

(defn wait-for-shutdown-promise [job]
  (while (not (realized? (:shutdown-promise job)))
    #_keep-spinning)
  (error {:message "shutdown-promise triggered"}))