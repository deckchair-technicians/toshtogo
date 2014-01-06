(ns toshtogo.agents
  (:require [clojure.java.jdbc :as sql]
            [toshtogo.sql :as tsql]
            [toshtogo.util :refer [uuid]]))

(defprotocol Agents
  (agent! [this agent-details]))

(def select-agent-sql
  "select
     *
   from
     agents
   where
     hostname           = :hostname
     and system_name    = :system_name
     and system_version = :system_version")

(defn agent-record [agent-details]
  (assoc
      (select-keys agent-details [:hostname :system_name :system_version])
    :agent_id (uuid)))

(deftype SqlAgents [cnxn]
  Agents
  (agent! [this agent-details]
    (if-let [agent (first (tsql/query cnxn select-agent-sql agent-details))]
      agent
      (let [agent-record (agent-record agent-details)]
        (tsql/insert! cnxn :agents agent-record)
        agent-record))))

(defn sql-agents [cnxn]
  (SqlAgents. cnxn))
