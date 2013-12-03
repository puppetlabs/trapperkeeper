(defproject puppetlabs/trapperkeeper "0.1.0-SNAPSHOT"
  :description "We are trapperkeeper.  We are one."
  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/kitchensink "0.3.0"]
                 [org.eclipse.jetty/jetty-server "7.6.1.v20120215"]
                 [ring/ring-servlet "1.1.8"]

                 [prismatic/plumbing "0.1.0"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  ;; Convenience for manually testing application shutdown support
  :aliases {"test-shutdown" ["trampoline" "run" "-m" "shutdown-app.test-shutdown"]}

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:test-paths ["test-resources"]}
             :test {:dependencies [[clj-http "0.5.3"]
                                   [org.slf4j/slf4j-log4j12 "1.7.5"]
                                   [puppetlabs/kitchensink "0.3.0" :classifier "test"]]}
             :testutils {:source-paths ^:replace ["test"]}}

  :main puppetlabs.trapperkeeper.main
  )
