(ns toshtogo.core
  (:require [toshtogo.migrations :refer [run-migrations!]]
            [toshtogo.web.handler :refer [app]]
            [ring.adapter.jetty :refer [run-jetty]]
            [watchtower.core :as watcher])
  (:gen-class))

(defn -main [& args]
  (run-migrations!)
  (run-jetty app {:port 3000}))

(defn reload-templates! [files]
  (require 'toshtogo.web.handler :reload-all))

(defn auto-reloading-start!
  "For interactive development ONLY"
  []
  ; Reload this namespace and its templates when one of the templates changes.
  (when-not (= (System/getenv "RING_ENV") "production")
    (watcher/watcher ["src"]
                     (watcher/rate 50) ; poll every 50ms
                     (watcher/file-filter (watcher/extensions :html))
                     (watcher/on-change reload-templates!))))
