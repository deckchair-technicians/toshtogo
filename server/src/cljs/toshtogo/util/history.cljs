(ns toshtogo.util.history
  (:import goog.History)
  (:require [secretary.core :as secretary]
            [goog.events :as events]
            [clojure.string :as s]
            [goog.history.EventType :as EventType]))

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

; TODO:
; Consider having access to app-state (mainly query) inside here, so that other
; components would not necessarily need to know anything concrete about it

(defn params->query-string
  [query-params]
  (s/join "&"
          (map (partial s/join "=")
               (map #(update-in % [0] name)
                    (-> query-params
                        (update-in [:outcome] (partial s/join ","))
                        (update-in [:job-types] (partial s/join ",")))))))

(defn update-query!
  [query]
  (let [hash (aget js/window "location" "hash")
        base (s/join (rest (first (s/split hash "?"))))]
    (navigate (str base "?" (params->query-string query)))))

(defn build-url
  [query]
  (let [hash (aget js/window "location" "hash")
        base (s/join (rest (first (s/split hash "?"))))]
    (str "#" base "?" (params->query-string query))))

(defn set-hash!
  ([v] (set-hash! (.-location js/window) v))
  ([loc v] (aset loc "hash" v)))
