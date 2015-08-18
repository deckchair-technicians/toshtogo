(ns toshtogo.client.clients.large-error-converting-client
  (:require [bowen.core :as bowen]
            [toshtogo.util
             [core :as util]
             [json :as json]]
            [toshtogo.client.protocol :refer :all]))

(defprotocol ErrorTransformer
  (transform [this error-body]
    "Returns a URL"))

(def identity-error-transformer
  (reify ErrorTransformer
    (transform [this error-body]
      error-body)))

(defn replace-error [{:keys [error] :as result} error-transformer]
  (try
    (util/with-timeout 30000 "Timed out"
      (let [new-error (transform error-transformer error)]
        (assoc result
          :error
          new-error)))

    (catch Throwable t
      (println t)
      (assoc result
        :error {:error-while-persisting-large-error (str t)}))))

(defn replace-large-errors-with-url
  "When the result is an error, this function inspects the size of the error body and,
   if it is 'large' sends it to ErrorPersister and replaces the error with {:error-url url}"
  [{:as result :keys [outcome error]}
   error-persister]
  (if (not= :error outcome)
    result
    (let [error-string ^String (json/encode error)]
      (if (> 50000 (.length error-string) )
        result
        (replace-error result error-persister)))))

(defn large-error-transforming-client [decorated error-transformer]
  (bowen/decorate decorated
    (reify
      Client
      (complete-work! [this commitment-id result]
        (let [result (replace-large-errors-with-url result error-transformer)]
          (complete-work! decorated commitment-id result))))))
