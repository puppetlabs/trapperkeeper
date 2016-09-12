(def ks-version "1.3.1")

(defproject puppetlabs/trapperkeeper "1.5.2-SNAPSHOT"
  :description "A framework for configuring, composing, and running Clojure services."

  :min-lein-version "2.7.0"

  :parent-project {:coords [puppetlabs/clj-parent "0.1.0-SNAPSHOT"]
                   :inherit [:managed-dependencies]}

  ;; Abort when version ranges or version conflicts are detected in
  ;; dependencies. Also supports :warn to simply emit warnings.
  ;; requires lein 2.2.0+.
  :pedantic? :abort
  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]
                 [org.clojure/tools.nrepl]
                 [org.clojure/tools.macro]
                 [org.clojure/core.async]

                 [org.slf4j/log4j-over-slf4j]
                 [ch.qos.logback/logback-classic]
                 ;; even though we don't strictly have a dependency on the following two
                 ;; logback artifacts, specifying the dependency version here ensures
                 ;; that downstream projects don't pick up different versions that would
                 ;; conflict with our version of logback-classic
                 [ch.qos.logback/logback-core]
                 [ch.qos.logback/logback-access]

                 [clj-time]
                 [me.raynes/fs]
                 [clj-yaml]

                 [prismatic/plumbing]
                 [prismatic/schema]

                 [org.codehaus.janino/janino "2.7.8"]
                 [beckon "0.1.1"]

                 [puppetlabs/typesafe-config "0.1.5"]
                 [puppetlabs/kitchensink ~ks-version]

                 ]

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
                   :dependencies [[puppetlabs/kitchensink ~ks-version :classifier "test"]]}

             :testutils {:source-paths ^:replace ["test"]}
             :uberjar {:aot [puppetlabs.trapperkeeper.main]
                       :classifiers ^:replace []}}

  :plugins [[lein-parent "0.3.0"]]

  :main puppetlabs.trapperkeeper.main
  )
