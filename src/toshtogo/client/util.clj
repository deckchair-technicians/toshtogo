(ns toshtogo.client.util
  (:import [toshtogo.client SenderException])
  (:require [toshtogo.util.core :refer [retry-until-success exponential-backoff]]))

(defmacro throw-500
  [& body]
  `(let [result# (do ~@body)]
     (if (and (:status result#) (< 499 (:status result#) 600))
       (throw (SenderException. (str result#)))
       result#)))

(defmacro until-successful-response
  "Calls body repeatedly until it gets a non-500 response"
  [{:keys [interval sleep-fn timeout max-retries] :or {sleep-fn (partial exponential-backoff 5000)} :as opts}
   & body]
  (list 'retry-until-success opts (cons 'throw-500 body)))

