(defproject savagematt.toshtogo/client "0.7.1-SNAPSHOT"

  :description "An asynchronous job manager"

  :dependencies [[org.clojure/clojure "1.7.0"]

                 [cheshire "5.3.0"]
                 [ring-mock "0.1.5"]
                 [http-kit "2.1.16"]

                 ; TODO: this should definitely not be here, but we use it for query strings
                 [compojure "1.1.6"]

                 [clj-time "0.6.0"]
                 [com.google.guava/guava "15.0"]
                 [me.raynes/fs "1.4.5"]
                 [org.clojure/math.numeric-tower "0.0.4"]
                 [org.flatland/useful "0.11.3"]
                 [savagematt/bowen "2.1"]
                 [savagematt/vice "0.13"]
                 [trptcolin/versioneer "0.1.0"]]

  :profiles {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                                  [midje "1.7.0"]
                                  [http-kit.fake "0.2.1"]]

                   :plugins      [[lein-midje "3.1.0"]
                                  [lein-ring "0.8.8"]
                                  [lein-set-version "0.4.1"]
                                  [midje-junit-formatter "0.1.0-SNAPSHOT"]]}})
