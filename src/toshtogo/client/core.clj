(ns toshtogo.client.core
  (:require [toshtogo.util.core :refer [uuid cause-trace debug]]
            [toshtogo.client.protocol :refer [success error]]
            [toshtogo.client.clients.sender-client :refer :all]
            [toshtogo.client.util :refer :all]
            [toshtogo.client.senders.decorators :refer :all]
            [toshtogo.client.senders.http-sender :refer :all]
            [toshtogo.client.senders.app-sender :refer :all]))

(defn sender
  [client-opts agent-details]
  (case (:type client-opts)
    :http
    (let [base-path (:base-path client-opts)]
      (assert base-path)
      (http-sender agent-details base-path))
    :app
    (let [app (:app client-opts)]
      (assert app)
      (app-sender agent-details app))))


(defn client
  "Either:

  :type     :app
  :app      <a ring app>

  or:

  :type     :http
  :base-url <some url>"
  [client-opts & {:keys [system version error-fn timeout]
                  :or {:system "unknown" :version "unknown"}
                  :as opts}]

  (let [sender     (sender client-opts (get-agent-details system version))
        retry-opts (select-keys opts [:error-fn :timeout])]

    (sender-client (apply default-decoration
                          sender
                          (flatten (seq retry-opts))))))
