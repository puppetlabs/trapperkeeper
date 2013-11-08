(defproject puppetlabs/trapperkeeper "0.1.0-SNAPSHOT"
  :description "We are trapperkeeper.  We are one."
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/kitchensink "0.1.0"]
                 [clj-http "0.5.3" :scope "test"]
                 [org.eclipse.jetty/jetty-server "7.6.1.v20120215"]
                 ;; We might need this one as well.
                 ;; [ring/ring-core "1.1.8"]
                 [ring/ring-servlet "1.1.8"]

                 [prismatic/plumbing "0.1.0"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  ;; Convenience for manually testing application shutdown support
  :aliases {"test-shutdown" ["trampoline" "run" "-m" "shutdown-app.main"]}

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:test-paths ["test-resources"]}
             :testutils {:source-paths ^:replace ["test"]}})
