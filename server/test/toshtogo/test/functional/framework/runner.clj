(ns toshtogo.test.functional.framework.runner
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.framework.test-ids :as test-ids]
            [toshtogo.test.functional.test-support :as ts]
            [flatland.useful.seq :refer [take-until]]
            [clojure.pprint :as pp]))

(defn cleanup [container]
  (reduce (fn [container step]
            (step container)
            container)
          container (:cleanup container)))

(defn add-cleanup [container f]
  (update container :cleanup #(conj % f)))

(defn run-step [container step]
  (try (let [result (step container)]
         (assert (:is-container result))
         result)
       (catch Exception e
         (assoc container :exception e))))

(defn raise-exception [{:keys [exception] :as container}]
  (if exception
    (throw exception)
    container))

(defn ->container []
  {:is-container true
   :cleanup      []
   :client       (ts/test-client)})


(defn run-steps [& steps]
  (println "\nRunning scenario with" (count steps) "steps")
  (->> steps
       (reductions run-step (->container))
       (take-until :exception)
       (last)
       (cleanup)
       (raise-exception)))

(defmacro scenario [description & steps]
  `(with-redefs [test-ids/ids (test-ids/->ids)
                 toshtogo.client.protocol/heartbeat-time 10]
     (fact ~description
       @ts/migrated-dev-db
       (run-steps ~@steps))))

; Syntactic sugar
(def given identity)
(def when-we identity)

(defn readable [s-expression]
  (str s-expression))

(defmacro then-expect
  [value assertion]
  (let [description (str (readable value) " => " (readable assertion))]
    `(fn [container#]
       (println ~description)
       (fact {:midje/description ~description}
         (~value container#) => ~assertion)
       container#)))

(def and-we identity)
