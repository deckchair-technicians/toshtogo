(ns toshtogo.client.senders.app-sender
  (:require [ring.mock.request :refer [request body]]

            [clojure.string :as str]

            [schema
             [core :as sch]]

            [toshtogo.schemas :refer [Agent]]

            [toshtogo.client.senders.protocol :refer :all]
            [toshtogo.util.json :as tjson]))

(sch/defn make-request
  [agent-details :- Agent
   app :- sch/Any
   method :- (sch/enum :get :put :post)
   location :- sch/Str
   message :- (sch/maybe clojure.lang.Associative)]
  (let [req (request
             method
             (str "http://toshtogo.test/" (str/replace-first location #"^/" "")))]
    (app (-> req
             (body (tjson/encode (assoc message :agent agent-details)))
             (assoc :content-type "application/json")))))

(sch/defrecord AppSender
    [agent-details :- Agent
     app :- sch/Any]

  Sender
  (POST! [{:keys [agent-details app]} location message]
         (make-request agent-details app :post location message))

  (PUT! [{:keys [agent-details app]} location message]
        (make-request agent-details app :put location message))

  (GET [{:keys [app]} location]
      (app (request :get (str "http://toshtogo.test/" (str/replace-first location #"^/" ""))))))

(sch/defn app-sender
  [agent-details :- Agent
   app :- sch/Any]
  (->AppSender agent-details app))
