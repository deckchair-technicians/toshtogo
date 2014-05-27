(defproject savagematt/toshtogo "0.5.9"

  :description "An asynchronous job manager"

  :dependencies [[cheshire "5.3.0"]
                 [clj-time "0.6.0"]
                 [com.cemerick/url "0.1.0"]
                 [com.dbdeploy/dbdeploy-core "3.0M3"]
                 [com.google.guava/guava "15.0"]
                 [compojure "1.1.6"]
                 [enlive "1.1.5"]
                 [honeysql "0.4.3"]
                 [http-kit "2.1.16"]
                 [me.raynes/fs "1.4.5"]
                 [org.clojure/clojure "1.5.1"]
                 [org.clojure/java.jdbc "0.3.2"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.flatland/useful "0.10.3"]
                 [pallet-map-merge "0.1.0"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [prismatic/schema "0.2.1"]
                 [ring/ring-jetty-adapter "1.2.1"]
                 [ring/ring-json "0.2.0"]
                 [ring-mock "0.1.5"]
                 [savagematt/hermit "0.7"]
                 [swiss-arrows "1.0.0"]
                 [trptcolin/versioneer "0.1.0"]
                 [watchtower "0.1.1"]]

  :plugins [[lein-ring "0.8.8"]
            [s3-wagon-private "1.1.2"]]

  :main toshtogo.server.core

  :ring {:handler toshtogo.server.core/dev-app-instance :reload-paths ["src"]}

  ;:jvm-opts ["-agentpath:/Applications/YourKit_Java_Profiler_2013_build_13082.app/bin/mac/libyjpagent.jnilib"]
  :aot [toshtogo.util.OptimisticLockingException
        toshtogo.client.senders.SenderException
        toshtogo.client.senders.RecoverableException
        toshtogo.client.BadRequestException]

  :profiles {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                                  [midje "1.6.2"]]
                   :plugins      [[lein-midje "3.1.0"]
                                  [midje-junit-formatter "0.1.0-SNAPSHOT"]]}
             })
