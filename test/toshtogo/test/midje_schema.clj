(ns toshtogo.test.midje-schema
  (:require [schema.core :as sch]
            [schema.coerce :as coer]
            [schema.utils :as utils]
            [schema.macros :as macros]
            [midje.checking.core :refer [as-data-laden-falsehood]]
            [clj-time.core :refer [now minutes seconds millis plus minus after? interval within?]]
            [clojure.pprint :refer [pprint]]
            [clojure.stacktrace :refer [print-cause-trace]]
            ))

(def timestamp-tolerance (seconds 5))

(defn close-to
      ([expected]
       (close-to expected timestamp-tolerance))
  ([expected tolerance-period]
   (let [start (minus expected tolerance-period)
         end (plus expected tolerance-period)]
     (sch/pred (fn [x] (within? start end x))
               (str "Within " tolerance-period " of " expected)))))


(def is-nil (sch/pred nil? "is nil"))

(defn pp-str [x]
  (with-out-str (pprint x)))


(defn when-sorted
      ([schema]
       (when-sorted schema identity))
  ([schema sorter]
   (with-meta schema {:coercer #(sort-by sorter %)})))

(deftype IsSchema [expected]
  sch/Schema
  (walker [this]
    (fn [x]
      (if (= x expected)
        x
        (macros/validation-error expected x expected))))
  (explain [this]
    expected))

(defn is [expected]
      (IsSchema. expected))

(defn build-schemas [item-schemas]
  (vec (map-indexed (fn [i s] {:schema s
                               :name   (str "item " i)
                               :walker (sch/subschema-walker s)})
                    item-schemas)))

(defn in-any-order [& schemas]
  (reify sch/Schema
    (walker [this]
      (let [item-schemas (build-schemas schemas)
            err-conj (utils/result-builder (constantly []))]
        (fn [xs]
          (loop [xs xs
                 remaining-item-schemas item-schemas
                 out []]
            (if (empty? remaining-item-schemas)

              (if (empty? xs)
                ; no remaining schemas, no remaining items
                out

                ; more items than schemas
                (err-conj out (macros/validation-error nil xs (list 'has-extra-elts? xs))))

              (if (empty? xs)
                ; more schemas than items
                (err-conj out
                          (macros/validation-error
                            (vec (map :schema remaining-item-schemas))
                            nil
                            (list* 'missing-items? (map :schema remaining-item-schemas))))

                (let [x (first xs)
                      match (->> remaining-item-schemas
                                 (filter (fn [item-schema]
                                           (not (utils/error-val ((:walker item-schema) x)))))
                                 first)]
                  (recur (rest xs)
                         (remove #{match} remaining-item-schemas)
                         (if match
                           out
                           (err-conj out
                                     (macros/validation-error
                                       nil
                                       xs
                                       (list* 'present?
                                              [x]))))))))))))

    (explain [this]
      (let [item-schemas (build-schemas schemas)]
        (vec
          (for [s item-schemas]
            (list (sch/explain (:schema s)) (:name s))))))))

(defn in-order [& schemas]
  (reify sch/Schema
    (walker [this]
      (let [item-schemas (build-schemas schemas)
            err-conj (utils/result-builder (constantly []))]
        (fn [xs]
          (loop [xs xs
                 item-schemas item-schemas
                 out []]
            (if (empty? item-schemas)

              (if (empty? xs)
                ; no remaining schemas, no remaining items
                out

                ; more items than schemas
                (err-conj out (macros/validation-error nil xs (list 'has-extra-elts? xs))))

              (if (empty? xs)
                ; more schemas than items
                (err-conj out
                          (macros/validation-error
                            (vec (map :schema item-schemas))
                            nil
                            (list* 'missing-items? (map :schema item-schemas))))

                (let [x (first xs)
                      item-schema (first item-schemas)]
                  (recur (rest xs)
                         (rest item-schemas)
                         (err-conj out ((:walker item-schema) x))))))))))

    (explain [this]
      (let [item-schemas (build-schemas schemas)]
        (vec
          (for [s item-schemas]
            (list (sch/explain (:schema s)) (:name s))))))))

(defn match [schema]
  (sch/start-walker
    (fn [s]
      (let [walk (sch/walker (cond
                               (map? s)
                               (assoc s sch/Any sch/Any)

                               (not (satisfies? sch/Schema s))
                               (is s)

                               :else
                               s))]
        (fn [x]
          (if-let [coercer (:coercer (meta s))]
            (walk (coercer x))
            (walk x)))))
    schema))

(defn check [schema x]
  (-> x
      ((match schema))
      utils/error-val))

(defn matches [schema]
  (assert schema "No schema provided")
  (fn [thing]
    (try
      (assert thing "Nothing to validate")

      (if-let [errors (check schema thing)]
        (as-data-laden-falsehood {:notes [(with-out-str (pprint errors))]})
        true)

      (catch Exception e
        (as-data-laden-falsehood {:notes [(with-out-str (print-cause-trace e))]})))))
