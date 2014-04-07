(ns toshtogo.server.agents.sql
  (:require [clojure.java.jdbc :as sql]
            [honeysql.helpers :refer :all]
            [toshtogo.util.sql :as tsql]
            [toshtogo.util.hsql :as hsql]
            [toshtogo.util.core :refer [uuid]]
            [toshtogo.server.agents.protocol :refer :all]))

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

(deftype SqlAgents [cnxn]
  Agents
  (agent! [this agent-details]
    (if-let [agent (first (hsql/query cnxn select-agent :params agent-details))]
      agent
      (let [agent-record (agent-record agent-details)]
        (tsql/insert! cnxn :agents agent-record)
        agent-record))))

(defn sql-agents [cnxn]
  (SqlAgents. cnxn))
