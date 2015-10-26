(ns toshtogo.util.history
  (:import goog.History)
  (:require [secretary.core :as secretary ]
            [goog.events :as events]
            [goog.history.EventType :as EventType])
  )

(defonce history
  (History.))

(defn navigate
  [location]
  (. history (setToken location "")))

(defonce event-listener
  (events/listen history EventType/NAVIGATE #(secretary/dispatch! (.-token %))))

(defn enable-history!
  []
  (.setEnabled history true))

