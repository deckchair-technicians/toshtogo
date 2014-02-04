(ns toshtogo.migrations
  (:require  [clojure.java.io :as io]
             [clojure.java.jdbc :as sql]
             [me.raynes.fs :as fs])
  (:import [java.security CodeSource]
           [java.net URL]
           [java.util.zip ZipInputStream]
           [com.dbdeploy DbDeploy]))

(defn code-source
  "utility function to get the name of jar in which this function is invoked"
  [& [ns]]
  (-> (or ns (class *ns*))
      .getProtectionDomain
      .getCodeSource))

(defn get-resources [prefix]
  (let [csource ^CodeSource (code-source)
        jar ^URL (.getLocation csource)
        zip (ZipInputStream. (.openStream jar))]
    (loop [resources []]
      (let [next-entry (.getNextEntry zip)]
        (if (nil? next-entry)
          resources
          (let [name (.getName next-entry)
                new-resources (if (.startsWith name prefix) (conj resources name) resources)]
            (recur new-resources)))))))

(defn extract-migrations!
  "Extracts the migrations"
  []
  (let [migration-paths (get-resources "migrations/")
        temp-dir (fs/temp-dir "toshtogo_migrations")]
    (doseq [path migration-paths]
      (spit (str temp-dir "/" (fs/base-name path))
            (slurp (io/resource path))))
    temp-dir))

(def create-changelog-sql "
     create table if not exists changelog (
       change_number bigint not null primary key,
       complete_dt timestamp not null,
       applied_by varchar(100) not null,
       description varchar(500) not null
     );")

(defn run-migrations!
  [db]
  (sql/execute! db [create-changelog-sql])
  (let [temp-dir (extract-migrations!)
        db-deploy (doto (DbDeploy.)
                    (.setUrl (format "jdbc:%s:%s" (db :subprotocol) (db :subname)))
                    (.setDriver (db :classname))
                    (.setUserid (db :user))
                    (.setPassword (db :password))
                    (.setScriptdirectory temp-dir))]
    (.go db-deploy)))
