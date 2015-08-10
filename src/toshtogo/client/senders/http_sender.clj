(ns toshtogo.client.senders.http-sender
  (:require [org.httpkit.client :as http]

            [flatland.useful.map :refer [update]]

            [toshtogo.util
             [json :as tjson]]

            [toshtogo.client
             [util :refer [url-str]]]

            [schema
             [core :as sch]]

            [vice.valuetypes :refer [Url]]

            [toshtogo.schemas :refer [Agent]]

            [toshtogo.client.senders.protocol :refer :all])

  (:import [java.io InputStream]))

(defmulti ensure-str class)
(defmethod ensure-str :default [x] (str x))
(defmethod ensure-str InputStream [x] (slurp x))

(defn- slurp-body
  [resp]
  (update resp :body ensure-str))

(defn post [[url body]]
  @(http/post url body))

(defn put [[url body]]
  @(http/put url body))

(sch/defn url-and-body
  [base-path :- Url
   agent-details :- Agent
   location :- sch/Str
   message :- (sch/maybe clojure.lang.Associative)]
  [(url-str base-path location)
   {:body    (tjson/encode (assoc message :agent agent-details))
    :headers {"Content-Type" "application/json"}}])

(sch/defrecord HTTPSender
    [agent-details :- Agent
     base-path :- Url]

  Sender
  (POST! [{:keys [base-path agent-details]} location message]
         (slurp-body (post (url-and-body base-path agent-details location message))))

  (PUT! [{:keys [base-path agent-details]} location message]
        (slurp-body (put (url-and-body base-path agent-details location message))))

  (GET [{:keys [base-path]} location]
      (slurp-body @(http/get (url-str base-path location)))))

(sch/defn http-sender :- HTTPSender
  [agent-details :- Agent
   base-path :- Url]
  (->HTTPSender agent-details base-path))
