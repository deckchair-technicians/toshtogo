(ns toshtogo.web.idempotentput
  (:require [toshtogo.util :refer [ppstr]]
            [toshtogo.sql :as tsql])
  (:import [toshtogo.web IdempotentPutException]))

(defn put-is-identical?
  [previous-put hash-1 hash-2]
  (and (= hash-1 (previous-put :hash_1))
       (= hash-2 (previous-put :hash_2))))

(defn check-idempotent!
  [cnxn body-hash id on-first-attempt on-second-attempt]
  (let [[hash-1 hash-2] body-hash
        previous-put   (first (tsql/query cnxn "select * from put_hashes where id = :id" {:id id}))]

    (if (not previous-put)

      (do (tsql/insert! cnxn
                        :put_hashes
                        {:id id :hash_1 hash-1 :hash_2 hash-2})
          (on-first-attempt))

      (if (put-is-identical? previous-put hash-1 hash-2)
        (on-second-attempt)
        (throw (IdempotentPutException.
                (str "Previous put for " id
                     " had hash \n"
                     [(previous-put :hash_1) (previous-put :hash_2)]
                     "\n which does not match "
                     body-hash)))))))
