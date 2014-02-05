(ns toshtogo.client.core
  (:require [toshtogo.util.core :refer [uuid cause-trace debug]]
            [toshtogo.client.protocol :refer [success error]]
            [toshtogo.client.clients.sender-client :refer :all]
            [toshtogo.client.util :refer :all]
            [toshtogo.client.senders.decorators :refer :all]
            [toshtogo.client.senders.http-sender :refer :all]
            [toshtogo.client.senders.app-sender :refer :all]))

(defn decorate-sender [sender & {:keys [error-fn]}]
  (debug-sender
    false
    (json-sender
      (following-sender
        (retry-sender sender :error-fn error-fn)))))

(defn http-sender
  ([base-path]
   (http-sender base-path "unknown" "unknown"))
  ([base-path system version]
   (decorate-sender )))

(defn sender
  [client-opts agent-details]
  (case (:type client-opts)
    :http
    (http-sender agent-details (:base-path client-opts))
    :app
    (app-sender agent-details (:app client-opts))))


(defn client
  "Either:

  :type     :app
  :app      <a ring app>

  or:

  :type     :http
  :base-url <some url>"
  [client-opts & {:keys [system version error-fn] :or {:system "unknown" :version "unknown" :error-fn nil} :as opts}]
  (sender-client (decorate-sender
                   (sender client-opts (get-agent-details system version))
                   :error-fn error-fn)))
