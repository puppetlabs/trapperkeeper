(defproject puppetlabs/trapperkeeper "0.1.0-SNAPSHOT"
  :description "We are trapperkeeper.  We are one."
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/kitchensink "0.1.0"]
                 [prismatic/plumbing "0.1.0"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:test-paths ["test-resources"]}
             :testutils {:source-paths ^:replace ["test"]}})
