(ns toshtogo.client.senders.http-sender
  (:require [org.httpkit.client :as http]
            [cemerick.url :refer [url]]
            [clojure.string :as str]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.json :as tjson]
            [toshtogo.util.core :refer [debug]]
            [toshtogo.client.senders.protocol :refer :all])
  (:import (java.io InputStream)
           (java.lang Exception)
           (toshtogo.client.senders RecoverableException)
           (java.util.concurrent ExecutionException))  )

(defmulti ensure-str class)
(defmethod ensure-str :default [x] (str x))
(defmethod ensure-str InputStream [x] (slurp x))

(defn throw-recoverable-exception [promise]
  (try
    @promise
    (catch Exception e
      (if (instance? ExecutionException e)
        (throw (RecoverableException. (.getCause e)))
        (throw (RecoverableException. e)))))                                      )

(defn post [[url body]]
  (throw-recoverable-exception (http/post url body)))

(defn put [[url body]]
  (throw-recoverable-exception (http/put url body)))

(defn http-sender [agent-details base-path]
  (assert agent-details)
  (assert base-path)

  (letfn [(url-of
            [location]
            (str (url base-path location)))

          (url-and-body
            [location message]
            [(url-of location)
             {:body    (tjson/encode (assoc message :agent agent-details))
              :headers {"Content-Type" "application/json"}}])

          (slurp-body
            [resp]
            (update resp :body ensure-str))]

    (reify Sender
      (POST! [this location message]
        (slurp-body (post (url-and-body location message))))

      (PUT! [this location message]
        (slurp-body (put (url-and-body location message))))

      (GET [this location]
        (slurp-body @(http/get (url-of location)))))))
