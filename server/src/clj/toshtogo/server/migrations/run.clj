(ns toshtogo.server.migrations.run
  (:require  [clojure.java.io :as io]
             [clojure.java.jdbc :as sql]
             [me.raynes.fs :as fs]
             [hermit.core :as hermit])
  (:import [com.dbdeploy DbDeploy]
           (com.dbdeploy.database DelimiterType)))

(def create-changelog-sql "
CREATE TABLE IF NOT EXISTS changelog (
  change_number BIGINT NOT NULL primary key,
  complete_dt TIMESTAMP NOT NULL,
  applied_by VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL
);")

(defn acquire-advisory-lock!
  [cnxn]
  (when-not
      (:pg_try_advisory_lock (first (sql/query cnxn ["select pg_try_advisory_lock(1);"])))
    (println "Lock in use.. retrying")
    (Thread/sleep 5000)
    (recur cnxn)))

(defn release-advisory-lock!
  [cnxn]
  (sql/query cnxn ["select pg_advisory_unlock(1);"]))

(defn run-migrations!
  [db]
  (sql/execute! db [create-changelog-sql])

  (sql/db-transaction
   [cnxn db]
   (try
     (acquire-advisory-lock! cnxn)

     (let [temp (fs/temp-dir "toshtogo_migrations")]
       (hermit/copy-resources! "toshtogo/server/migrations/reference-file" temp)

       (let [db-deploy (doto (DbDeploy.)
                         (.setUrl (format "jdbc:%s:%s" (db :subprotocol) (db :subname)))
                         (.setDriver (db :classname))
                         (.setUserid (db :user))
                         (.setPassword (db :password))
                         (.setScriptdirectory temp)
                         (.setDelimiter "---")
                         (.setDelimiterType DelimiterType/row))]
         (.go db-deploy)))

     (finally
       (release-advisory-lock! cnxn)))))
