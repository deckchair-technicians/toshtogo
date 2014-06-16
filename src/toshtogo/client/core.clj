(ns toshtogo.client.core
  (:require [toshtogo.util.core :refer [uuid cause-trace debug]]
            [toshtogo.client.protocol :refer [success error]]
            [toshtogo.client.clients.sender-client :refer :all]
            [toshtogo.client.clients.json-converting-client :refer :all]
            [toshtogo.client.util :refer :all]
            [toshtogo.client.senders.decorators :refer :all]
            [toshtogo.client.senders.http-sender :refer :all]
            [toshtogo.client.senders.app-sender :refer :all]))

(defn sender
  [client-opts agent-details]
  (case (:type client-opts)
    :http
    (let [base-url (:base-url client-opts)]
      (assert base-url)
      (http-sender agent-details base-url))
    :app
    (let [app (:app client-opts)]
      (assert app)
      (app-sender agent-details app))))


(defn client
  "Either:\n
\n
  :type     :app\n
  :app      <a ring app>\n
\n
  or:\n
\n
  :type     :http\n
  :base-url <some url>\n
\n
opts are:\n
  :agent-details a map of e.g. {:hostname \"my-machine\" :system_name \"name\" :system_version \"1.1\"}.\n
  See toshtogo.client.util/agent-details for a convenience function to build this map
  \n
  :error-fn an arity 1 function which is called with any Exceptions thrown by the client before it retries\n
  \n
  :timeout integer milliseconds after which the client will give up trying to send a message to the server and re-throw the error\n
  \n
  :debug boolean set this to true to send requests and responses to stdout"
  [client-opts & {:keys [agent-details error-fn timeout debug should-retry]
                  :or {agent-details {:hostname @hostname :system_name "unknown" :system_version "unknown"}}
                  :as opts}]

  (let [sender          (sender client-opts agent-details)
        decoration-opts (select-keys opts [:error-fn :timeout :debug :should-retry])]
    (json-converting-client
      (sender-client (apply wrap-decoration
                            sender
                            (flatten (seq decoration-opts)))))))
