(defproject puppetlabs/trapperkeeper "0.1.0-SNAPSHOT"
  :description "We are trapperkeeper.  We are one."
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [puppetlabs/clj-utils "0.1.0-SNAPSHOT"]
                 [prismatic/plumbing "0.1.0"]]

  :repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
                 ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]

  :profiles {:dev {:test-paths ["test-resources"]}})
