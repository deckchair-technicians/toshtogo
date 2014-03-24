(defproject savagematt/toshtogo "0.4.11-SNAPSHOT"

  :description "An asynchronous job manager"

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [compojure "1.1.6"]
                 [swiss-arrows "1.0.0"]
                 [org.flatland/useful "0.10.3"]
                 [me.raynes/fs "1.4.5"]
                 [cheshire "5.3.0"]
                 [cheshire "5.3.0"]
                 [org.clojure/java.jdbc "0.3.2"]
                 [honeysql "0.4.3"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [ring/ring-jetty-adapter "1.2.1"]
                 [ring/ring-json "0.2.0"]
                 [ring-mock "0.1.5"]
                 [http-kit "2.1.16"]
                 [com.cemerick/url "0.1.0"]
                 [clj-time "0.6.0"]
                 [enlive "1.1.5"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [com.google.guava/guava "15.0"]
                 [com.dbdeploy/dbdeploy-core "3.0M3"]
                 [watchtower "0.1.1"]
                 [pallet-map-merge "0.1.0"]
                 [trptcolin/versioneer "0.1.0"]]

  :plugins [[lein-ring "0.8.8"]
            [s3-wagon-private "1.1.2"]]

  :main toshtogo.server.core

  :ring {:handler toshtogo.server.core/dev-app-instance :reload-paths ["src"]}

  :aot [toshtogo.server.util.IdempotentPutException
        toshtogo.util.OptimisticLockingException
        toshtogo.client.senders.SenderException]

  :profiles {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                                  [midje "1.6.2"]]
                   :plugins [[lein-midje "3.1.0"]]}
})
