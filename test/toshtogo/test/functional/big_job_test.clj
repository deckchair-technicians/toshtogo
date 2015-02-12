(ns toshtogo.test.functional.big-job-test
  (:require [midje.sweet :refer :all]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.util.core :refer [uuid uuid-str debug]]
            [toshtogo.util.json :refer [decode]]
            [toshtogo.test.functional.test-support :refer :all])
  (:import (java.util UUID)))

(background (before :contents @migrated-dev-db))

(defn big-request []
  {:data (take 1000 (repeatedly #(uuid-str)))})

(future-fact
  "We can handle large request bodies (current fails because the text is too big for Postgres to index)"
  (put-job! client (uuid) (job-req (big-request) (uuid-str))))
