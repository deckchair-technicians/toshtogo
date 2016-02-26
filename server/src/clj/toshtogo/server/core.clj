(ns toshtogo.server.core
  (:require [toshtogo.server.migrations.run :refer [run-migrations!]]
            [toshtogo.server.handler :refer [app]]
            [toshtogo.server.logging :refer :all]
            [org.httpkit.server :refer [run-server]])
  (:import [toshtogo.server.logging SysLogger])
  (:gen-class))

(def dev-db {:classname   "org.postgresql.Driver"           ; must be in classpath
             :subprotocol "postgresql"
             :subname     "//localhost:5432/toshtogo"
             :user        "postgres"
             :password    ""})

(defn dev-app [& {:keys [debug logger-factory db]
                  :or   {debug false
                         db    dev-db}}]
  (app db
       :debug debug
       :logger-factory (or logger-factory
                           (if debug (constantly (SysLogger.))
                                     (constantly nil)))))

(defn start! [debug port join?]
  (run-migrations! dev-db)
  (run-server (dev-app :debug debug) {:port port :join? join?}))

(defn -main [& {debug "-debug"}]
  (start! (= "yes" debug) 3001 true))

