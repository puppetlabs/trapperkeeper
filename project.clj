(defproject puppetlabs/trapperkeeper "0.1.0-SNAPSHOT"
  :description "We are trapperkeeper.  We are one."
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/kitchensink "0.1.0-SNAPSHOT"]
                 [prismatic/plumbing "0.1.0"]
                 [log4j "1.2.17" :exclusions [javax.mail/mail
                                              javax.jms/jms
                                              com.sun.jdmk/jmxtools
                                              com.sun.jmx/jmxri]]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :profiles {:dev {:test-paths ["test-resources"]}})
