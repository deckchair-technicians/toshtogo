(ns toshtogo.client.senders.protocol)

(defprotocol Sender
  (POST! [this location message])
  (PUT! [this location message])
  (GET [this location]))
