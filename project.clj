(def ks-version "1.0.0")

(defproject puppetlabs/trapperkeeper "1.1.1"
  :description "A framework for configuring, composing, and running Clojure services."
  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [clj-time "0.5.1"]
                 [puppetlabs/kitchensink ~ks-version]
                 [prismatic/plumbing "0.4.2"]
                 [prismatic/schema "0.4.0"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [org.clojure/tools.macro "0.1.2"]
                 [ch.qos.logback/logback-classic "1.1.1"]
                 [org.codehaus.janino/janino "2.7.8"]
                 [puppetlabs/typesafe-config "0.1.1"]
                 [me.raynes/fs "1.4.5"]
                 [clj-yaml "0.4.0"]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  ;; Convenience for manually testing application shutdown support - run `lein test-external-shutdown`
  :aliases {"test-external-shutdown" ["trampoline" "run" "-m" "examples.shutdown-app.test-external-shutdown"]}

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:dev {:source-paths ["examples/shutdown_app/src"
                                  "examples/java_service/src/clj"]
                   :java-source-paths ["examples/java_service/src/java"]
                   :dependencies [[spyscope "0.1.4"]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test"]]
                   :injections [(require 'spyscope.core)]}

             :testutils {:source-paths ^:replace ["test"]}
             :uberjar {:aot [puppetlabs.trapperkeeper.main]
                       :classifiers ^:replace []}}

  :plugins [[lein-release "1.0.5" :exclusions [org.clojure/clojure]]]

  :main puppetlabs.trapperkeeper.main
  )
