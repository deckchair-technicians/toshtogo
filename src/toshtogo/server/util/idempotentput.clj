(ns toshtogo.server.util.idempotentput
  (:require [honeysql.helpers :refer :all]
            [toshtogo.util.hsql :as hsql]
            [toshtogo.util.sql :as sql])
  (:import [toshtogo.server.util IdempotentPutException]))

(defn put-is-identical?
  [previous-put hash-1 hash-2]
  (and (= hash-1 (previous-put :hash_1))
       (= hash-2 (previous-put :hash_2))))

(defn check-idempotent!
  [cnxn body-hash operation-type id on-first-attempt on-second-attempt]
  (let [[hash-1 hash-2] body-hash
        operation-type  (name operation-type)
        previous-put    (first (hsql/query
                                cnxn
                                (-> (select :*)
                                    (from :put_hashes)
                                    (where [:and
                                            [:= :id id]
                                            [:= :operation_type operation-type]]))))]

    (if (not previous-put)

      (do (sql/insert! cnxn
                        :put_hashes
                        {:id id :operation_type operation-type :hash_1 hash-1 :hash_2 hash-2})
          (on-first-attempt))

      (if (put-is-identical? previous-put hash-1 hash-2)
        (on-second-attempt)
        (throw (IdempotentPutException.
                (str "Previous put for " operation-type "/" id
                     " had hash \n"
                     [(previous-put :hash_1) (previous-put :hash_2)]
                     "\n which does not match "
                     body-hash)))))))
