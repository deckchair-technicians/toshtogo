(ns toshtogo.server.agents.protocol)

(defprotocol Agents
  (agent! [this agent-details]))
