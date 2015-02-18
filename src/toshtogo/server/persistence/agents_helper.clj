(ns toshtogo.server.persistence.agents-helper
  (:require [honeysql.helpers :refer :all]

            [toshtogo.util
             [core :refer [uuid]]]))

(def select-agent
  (-> (select :*)
      (from :agents)
      (where [:and
              [:= :hostname       :?hostname]
              [:= :system_name    :?system_name]
              [:= :system_version :?system_version]])))

(defn agent-record [agent-details]
  (assoc
      (select-keys agent-details [:hostname :system_name :system_version])
    :agent_id (uuid)))
