(ns toshtogo.config)

(def dbs {:dev {:classname "org.postgresql.Driver" ; must be in classpath
           :subprotocol "postgresql"
           :subname "//localhost:5432/toshtogo"
           :user "postgres"
           :password "postgres"}})

(def db (dbs (or (keyword (System/getenv "toshtogo_env")) :dev)))
