(ns toshtogo.client.core
  (:require [toshtogo.client.clients
             [sender-client :refer [sender-client]]
             [json-converting-client :refer [json-converting-client]]]

            [toshtogo.client.util :refer [hostname]]

            [toshtogo.client.clients.large-error-converting-client :as error-converting]
            [toshtogo.client.senders.decorators :refer [wrap-decoration]]

            [toshtogo.client.senders
             [app-sender :refer [app-sender]]
             [http-sender :refer [http-sender]]])
  (:import [java.net URL]))

(defn sender
  [{:keys [type base-url app]} agent-details]
  (case type
    :http
    (http-sender agent-details (if (instance? URL base-url) base-url (URL. base-url)))
    :app
    (app-sender agent-details app)))

(def printing-large-error-handler
  (reify
    error-converting/ErrorTransformer
    (transform [this error]
      (println "Large error encountered:")
      (clojure.pprint/pprint error)
      {:error "...was too large- check stdout from agent process"})))

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
  [client-opts & {:keys [agent-details error-fn timeout debug should-retry large-error-transformer]
                  :or   {agent-details           {:hostname @hostname :system_name "unknown" :system_version "unknown"}
                         large-error-transformer printing-large-error-handler}
                  :as   opts}]

  (let [sender          (sender client-opts agent-details)
        decoration-opts (select-keys opts [:error-fn :timeout :debug :should-retry])]
    (-> (sender-client (apply wrap-decoration
                              sender
                              (flatten (seq decoration-opts))))
        (error-converting/large-error-transforming-client large-error-transformer)
        (json-converting-client))))
