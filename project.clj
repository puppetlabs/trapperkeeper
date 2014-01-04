(defproject puppetlabs/trapperkeeper "0.3.0-SNAPSHOT"
  :description "We are trapperkeeper.  We are one."
  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [puppetlabs/kitchensink "0.4.1"]
                 [prismatic/plumbing "0.1.0"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [ch.qos.logback/logback-classic "1.0.13"]

                 ;; Jetty Webserver
                 [org.eclipse.jetty/jetty-servlet "7.6.1.v20120215"]
                 [org.eclipse.jetty/jetty-server "7.6.1.v20120215"
                  :exclusions [org.eclipse.jetty.orbit/javax.servlet]]

                 [ring/ring-servlet "1.1.8"]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  ;; Convenience for manually testing application shutdown support - run `lein test-external-shutdown`
  :aliases {"test-external-shutdown" ["trampoline" "run" "-m" "examples.shutdown-app.test-external-shutdown"]}

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :test-paths ["test/clj"]

  :profiles {:dev {:test-paths ["test-resources"]
                   :source-paths ["examples/shutdown_app/src"
                                  "examples/ring_app/src"
                                  "examples/servlet_app/src/clj"
                                  "examples/java_service/src/clj"]
                   :java-source-paths ["examples/servlet_app/src/java"
                                       "examples/java_service/src/java"]}

             :test {:dependencies [[clj-http "0.5.3"]
                                   [puppetlabs/kitchensink "0.4.0" :classifier "test"]]
                    :java-source-paths ["test/java"]}

             :testutils {:source-paths ^:replace ["test/clj"]
                         :java-source-paths ^:replace ["test/java"]}
             :uberjar {:aot [puppetlabs.trapperkeeper.main]
                       :classifiers ^:replace []}}

  :main puppetlabs.trapperkeeper.main
  )
