(ns ^:figwheel-always toshtogo.util.dates
  (:require [cljs-time.core :as t]
            [cljs-time.format :as tf]))

(def date-formatter (tf/formatter "yyyy-MM-dd"))
(defn date->string [d]
  (when d
    (if (string? d)
      d
      (tf/unparse date-formatter (if (instance? goog.date.DateTime d)
                                   d
                                   (t/date-time (.getFullYear d) (inc (.getMonth d)) (.getDate d)))))))

(defn unparse [formatter d]
  (tf/unparse formatter (if (instance? goog.date.DateTime d)
                          d
                          (t/date-time (.getFullYear d) (inc (.getMonth d)) (.getDate d)))))

(def time-string-formatter (tf/formatter "HH:mm:ss"))
(defn date->time-string [d]
  (when d
    (unparse time-string-formatter d)))

(def day-string-formatter (tf/formatter "EEE dth MMM"))
(defn date->day-string [d]
  (when d
    (tf/unparse day-string-formatter (if (instance? goog.date.DateTime d)
                                        d
                                        (t/date-time (.getFullYear d) (inc (.getMonth d)) (.getDate d))))))

(defn string->date [s]
  (when s
    (if (string? s)
      (tf/parse s)
      s)))

