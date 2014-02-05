(ns toshtogo.client.senders.http-sender
  (:import (java.io InputStream))
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [flatland.useful.map :refer [update]]
            [toshtogo.util.json :as tjson]
            [toshtogo.client.senders.protocol :refer :all]))

(defmulti ensure-str class)
(defmethod ensure-str :default [x] (.toString x))
(defmethod ensure-str InputStream [x] (slurp x))

(defn http-sender [agent-details base-path]
  (letfn [(url-and-body
            [location message]
            [(str base-path (str/replace-first location #"^/" ""))
              {:body (tjson/encode (assoc  message :agent agent-details))
               :headers {"Content-Type" "application/json"}}])
          (slurp-body [resp] (update resp :body ensure-str))]

    (reify Sender
      (POST! [this location message]
        (slurp-body @(apply http/post (url-and-body location message))))

      (PUT! [this location message]
        (slurp-body @(apply http/put (url-and-body location message))))

      (GET [this location]
        (slurp-body @(http/get (str base-path (str/replace-first location #"^/" ""))))))))
