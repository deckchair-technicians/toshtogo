(ns toshtogo.client.senders.http-sender
  (:require [org.httpkit.client :as http]

            [clojure
             [string :as str]
             [stacktrace :refer [print-cause-trace]]]

            [flatland.useful.map :refer [update]]

            [toshtogo.util
             [json :as tjson]
             [core :refer [debug]]]

            [toshtogo.client
             [util :refer [url-str]]]

            [toshtogo.client.senders.protocol :refer :all])

  (:import [java.io InputStream]))

(defmulti ensure-str class)
(defmethod ensure-str :default [x] (str x))
(defmethod ensure-str InputStream [x] (slurp x))

(defn post [[url body]]
  @(http/post url body))

(defn put [[url body]]
  @(http/put url body))

(defn http-sender [agent-details base-path]
  (assert agent-details)
  (assert base-path)

  (letfn [(url-of
            [location]
            (url-str base-path location))

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
