(ns toshtogo.test.functional.framework.runner
  (:require [midje.sweet :refer :all]
            [toshtogo.test.functional.framework.test-ids :as test-ids]
            [toshtogo.test.functional.test-support :as ts]
            [flatland.useful.seq :refer [take-until]]))

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

(defmacro scenario [& steps]
  `(with-redefs [test-ids/ids (test-ids/->ids)]
     (run-steps ~@steps)))

; Syntactic sugar
(def given identity)
(def when-we identity)
(defn then-expect [value assertion]
  (fn [container]
    (value container) => assertion
    container))

(def and-we identity)
