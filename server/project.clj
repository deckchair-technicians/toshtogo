(defproject savagematt.toshtogo/server "0.6.0"

  :description "An asynchronous job manager"

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [savagematt.toshtogo/client "0.5.30"]

                 [ring/ring-jetty-adapter "1.2.1"]
                 [ring-mock "0.1.5"]
                 [ring/ring-json "0.2.0"]

                 [com.dbdeploy/dbdeploy-core "3.0M3"]
                 [org.clojure/java.jdbc "0.3.2"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [honeysql "0.4.3"]

                 [savagematt/hermit "0.7"]
                 [watchtower "0.1.1"]]

  :main toshtogo.server.core

  :ring {:handler toshtogo.server.core/dev-app-instance :reload-paths ["src"]}

  ;:jvm-opts ["-agentpath:/Applications/YourKit_Java_Profiler_2013_build_13082.app/bin/mac/libyjpagent.jnilib"]

  :profiles {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                                  [midje "1.7.0"]
                                  [http-kit.fake "0.2.1"]]

                   :plugins      [[lein-midje "3.1.0"]
                                  [lein-ring "0.8.8"]
                                  [lein-set-version "0.4.1"]
                                  [midje-junit-formatter "0.1.0-SNAPSHOT"]]}})
