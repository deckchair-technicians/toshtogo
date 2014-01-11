(defproject toshtogo "0.1.0-SNAPSHOT"

  :description "An asynchronous job manager"

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [compojure "1.1.6"]
                 [org.flatland/useful "0.10.3"]
                 [me.raynes/fs "1.4.5"]
                 [cheshire "5.3.0"]
                 [org.clojure/java.jdbc "0.3.0-beta1"]
                 [postgresql/postgresql "8.4-702.jdbc4"]
                 [ring/ring-jetty-adapter "1.2.1"]
                 [ring/ring-json "0.2.0"]
                 [ring-mock "0.1.5"]
                 [clj-http "0.7.7"]
                 [clj-time "0.6.0"]
                 [enlive "1.1.5"]
                 [com.google.guava/guava "15.0"]
                 [com.dbdeploy/dbdeploy-core "3.0M3"]
                 [watchtower "0.1.1"]
                 [clj-http "0.7.8"]]

  :plugins [[lein-ring "0.8.8"]
            [s3-wagon-private "1.1.2"]]

  :main toshtogo.core

  :aot [toshtogo.web.IdempotentPutException]

  :ring {:handler toshtogo.handler/app}

  :profiles {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]                                                                 [midje "1.5.1"]]
                   :plugins [[lein-midje "3.1.0"]]}
})
