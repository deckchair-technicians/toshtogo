(ns toshtogo.test.functional.framework.test-ids
  (:require [toshtogo.util.core :refer [uuid uuid-str]]
            [clojure.pprint :as pp]))

(defn ensure-id [ids k]
  (if (get-in ids [:key->id k])
    ids
    (let [id (uuid-str)]
      (-> ids
          (assoc-in [:key->id k] id)
          (assoc-in [:id->key id] k)))
    ))

(defprotocol Ids
  (*id! [this k])
  (*id-desc [this id]))

(def ids
  "This is intended to be redef-ed in tests"
  (reify Ids
    (*id! [_this _k]
      (throw (IllegalStateException. "This is only meant to be used in the context of a scenario block")))
    (*id-desc [_this _id]
      (throw (IllegalStateException. "This is only meant to be used in the context of a scenario block")))))

(defn ->ids []
  (let [a (atom {:key->id {}
                 :id->key {}})]
    (reify Ids
      (*id! [_this k]
        (swap! a ensure-id k)
        (get-in @a [:key->id k]))

      (*id-desc [_this id]
        (get-in @a [:id->key id] id)))))

(defn id! [k]
  (*id! ids k))

(defn id-desc [id]
  (*id-desc ids id))