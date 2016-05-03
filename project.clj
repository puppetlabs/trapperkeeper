(def ks-version "1.3.0")
(def logback-version "1.1.3")

(defproject puppetlabs/trapperkeeper "1.4.0-SNAPSHOT"
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
                 [prismatic/schema "1.0.4"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [org.clojure/tools.macro "0.1.2"]
                 [ch.qos.logback/logback-classic ~logback-version]
                 ;; even though we don't strictly have a dependency on the following two
                 ;; logback artifacts, specifying the dependency version here ensures
                 ;; that downstream projects don't pick up different versions that would
                 ;; conflict with our version of logback-classic
                 [ch.qos.logback/logback-core ~logback-version]
                 [ch.qos.logback/logback-access ~logback-version]
                 [org.slf4j/log4j-over-slf4j "1.7.6"]
                 [org.codehaus.janino/janino "2.7.8"]
                 [puppetlabs/typesafe-config "0.1.5"]
                 [me.raynes/fs "1.4.6"]
                 [clj-yaml "0.4.0"]
                 [beckon "0.1.1"]
                 [org.clojure/core.async "0.2.374"]]

  :lein-release {:scm         :git
                 :deploy-via  :lein-deploy}

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  ;; Convenience for manually testing application shutdown support - run `lein test-external-shutdown`
  :aliases {"cljfmt" ["with-profile" "+cljfmt" "cljfmt"]
            "test-external-shutdown" ["trampoline" "run" "-m" "examples.shutdown-app.test-external-shutdown"]}

  ;; By declaring a classifier here and a corresponding profile below we'll get an additional jar
  ;; during `lein jar` that has all the code in the test/ directory. Downstream projects can then
  ;; depend on this test jar using a :classifier in their :dependencies to reuse the test utility
  ;; code that we have.
  :classifiers [["test" :testutils]]

  :profiles {:cljfmt {:plugins [[lein-cljfmt "0.5.0"]
                                [lein-parent "0.2.1"]]
                      :parent-project {:path "ext/pl-clojure-style/project.clj"
                                       :inherit [:cljfmt]}}
             :dev {:source-paths ["examples/shutdown_app/src"
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
