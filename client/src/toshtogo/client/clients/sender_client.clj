(ns toshtogo.client.clients.sender-client
  (:require [clojure.string :as s]
            [flatland.useful.map :refer [update-each]]
            [toshtogo.util.core :refer [uuid safe-name ensure-seq]]
            [toshtogo.client.protocol :refer :all]
            [toshtogo.client.senders.protocol :refer :all]
            [clojure.string :as str])
  (:import [java.net URLEncoder]
           [java.util Map]))

(defprotocol FormEncodeable
  (form-encode* [x encoding]))

(extend-protocol FormEncodeable
  String
  (form-encode* [unencoded encoding]
    (URLEncoder/encode unencoded encoding))
  Map
  (form-encode* [params encoding]
    (letfn [(encode [x] (form-encode* x encoding))
            (encode-param [[k v]] (str (encode (name k)) "=" (encode v)))]
      (->> params
           (mapcat
             (fn [[k v]]
               (if (or (seq? v) (sequential? v) )
                 (map #(encode-param [k %]) v)
                 [(encode-param [k v])])))
           (str/join "&"))))
  Object
  (form-encode* [x encoding]
    (form-encode* (str x) encoding)))

(defn form-encode
  "Encode the supplied value into www-form-urlencoded format, often used in
  URL query strings and POST request bodies, using the specified encoding.
  If the encoding is not specified, it defaults to UTF-8"
  [x & [encoding]]
  (form-encode* x (or encoding "UTF-8")))

(defn names [xs]
  (when xs (map name (ensure-seq xs))))

(defn to-query-string [query]
  (-> query
      (update :order-by #(map (fn [order-by]
                               (if (sequential? order-by)
                                 (s/join " " (map safe-name order-by))
                                 (safe-name order-by)))
                             %))
      (update-each [:job_type :outcome :fields] names)
      form-encode))

(defn sender-client [sender]
  (reify
    Client
    (put-job! [this job-id job-req]
      (PUT! sender
            (str "/api/jobs/" job-id)
            job-req))

    (get-job [this job-id]
      (GET sender (str "/api/jobs/" job-id)))

    (get-jobs [this query]
      (GET sender (str "/api/jobs?" (to-query-string query))))

    (get-graph [this graph-id]
      (GET sender (str "/api/graphs/" graph-id)))

    (get-job-types [this]
      (GET sender "/api/metadata/job_types"))

    (pause-job! [this job-id]
      (POST! sender
             (str "/api/jobs/" job-id "?action=pause")
             nil))

    (retry-job! [this job-id]
      (POST! sender
             (str "/api/jobs/" job-id "?action=retry")
             nil))

    (request-work! [this job-type-or-query]
      (let [query (if (not (map? job-type-or-query)) {:job_type (str job-type-or-query)} job-type-or-query)]
        (PUT! sender
              "/api/commitments"
              {:commitment_id (uuid)
               :query query})))

    (complete-work! [this commitment-id result]
      (PUT! sender
            (str "/api/commitments/" commitment-id)
            result))

    (heartbeat! [this commitment-id]
      (POST! sender (str "/api/commitments/" commitment-id "/heartbeat") {}))))
