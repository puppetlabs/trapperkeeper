(ns puppetlabs.trapperkeeper.bootstrap-test
  (:import (java.io StringReader))
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [file]]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :refer [without-ns]]
            [puppetlabs.kitchensink.classpath :refer [with-additional-classpath-entries]]
            [puppetlabs.trapperkeeper.services :refer [service-map]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.bootstrap :refer :all]
            [puppetlabs.trapperkeeper.logging :refer [reset-logging]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-with-empty-config parse-and-bootstrap]]
            [puppetlabs.trapperkeeper.examples.bootstrapping.test-services :refer [test-fn test-fn-two test-fn-three hello-world]]
            [schema.test :as schema-test]
            [me.raynes.fs :as fs]
            [clojure.string :as string]))

(use-fixtures
 :once
 schema-test/validate-schemas
 ;; Without this, "lein test NAMESPACE" and :only invocations may fail.
 (fn [f] (reset-logging) (f)))

(deftest bootstrapping
  (testing "Valid bootstrap configurations"
    (let [bootstrap-config "./dev-resources/bootstrapping/cli/bootstrap.cfg"
          app (parse-and-bootstrap bootstrap-config)]

      (testing "Can load a service based on a valid bootstrap config string"
        (let [test-svc (get-service app :TestService)
              hello-world-svc (get-service app :HelloWorldService)]
          (is (= (test-fn test-svc) :cli))
          (is (= (hello-world hello-world-svc) "hello world"))))

      (with-additional-classpath-entries ["./dev-resources/bootstrapping/classpath"]
        (testing "Looks for bootstrap config on classpath (dev-resources)"
          (with-test-logging
            (let [app (bootstrap-with-empty-config)
                  test-svc (get-service app :TestService)
                  hello-world-svc (get-service app :HelloWorldService)]
              (is (logged?
                   #"Loading bootstrap config from classpath: 'file:/.*dev-resources/bootstrapping/classpath/bootstrap.cfg'"
                   :debug))
              (is (= (test-fn test-svc) :classpath))
              (is (= (hello-world hello-world-svc) "hello world")))))

        (testing "Gives precedence to bootstrap config in cwd"
          (let [old-cwd (System/getProperty "user.dir")]
            (try
              (System/setProperty
               "user.dir"
               (.getAbsolutePath (file "./dev-resources/bootstrapping/cwd")))
              (with-test-logging
                (let [app (bootstrap-with-empty-config)
                      test-svc (get-service app :TestService)
                      hello-world-svc (get-service app :HelloWorldService)]
                  (is (logged?
                       #"Loading bootstrap config from current working directory: '.*/dev-resources/bootstrapping/cwd/bootstrap.cfg'"
                       :debug))
                  (is (= (test-fn test-svc) :cwd))
                  (is (= (hello-world hello-world-svc) "hello world"))))
              (finally (System/setProperty "user.dir" old-cwd)))))

        (testing "Gives precedence to bootstrap config specified as CLI arg"
          (with-test-logging
            (let [bootstrap-path "./dev-resources/bootstrapping/cli/bootstrap.cfg"
                  app (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-path])
                  test-svc (get-service app :TestService)
                  hello-world-svc (get-service app :HelloWorldService)]
              (is (logged?
                   (format "Loading bootstrap configs:\n%s" bootstrap-path)
                   :debug))
              (is (= (test-fn test-svc) :cli))
              (is (= (hello-world hello-world-svc) "hello world")))))))

    (testing "Invalid bootstrap configurations"
      (testing "Bootstrap config path specified on CLI does not exist"
        (let [cfg-path "./dev-resources/bootstrapping/cli/non-existent-bootstrap.cfg"]
          (is (thrown-with-msg?
               IllegalArgumentException
               #"Specified bootstrap config file does not exist: '.*non-existent-bootstrap.cfg'"
               (bootstrap-with-empty-config ["--bootstrap-config" cfg-path])))))

      (testing "No bootstrap config found"
        (is (thrown-with-msg?
             IllegalStateException
             #"Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath"
             (bootstrap-with-empty-config)))
        (let [got-expected-exception (atom false)]
          (try+
            (bootstrap-with-empty-config ["--bootstrap-config" nil])
            (catch map? m
              (is (contains? m :type))
              (is (= :cli-error (without-ns (:type m))))
              (is (= :puppetlabs.kitchensink.core/cli-error (:type m)))
              (is (contains? m :message))
              (is (re-find
                   #"Missing required argument for.*--bootstrap-config"
                   (m :message)))
              (reset! got-expected-exception true)))
          (is (true? @got-expected-exception))))

      (testing "Bad line in bootstrap config file"
        (let [bootstrap-config "./dev-resources/bootstrapping/cli/invalid_entry_bootstrap.cfg"]
          (is (thrown-with-msg?
               IllegalArgumentException
               #"(?is)Invalid line in bootstrap.*This is not a legit line"
               (parse-and-bootstrap bootstrap-config)))))

      (testing "Invalid service graph"
        (let [bootstrap-config "./dev-resources/bootstrapping/cli/invalid_service_graph_bootstrap.cfg"]
          (is (thrown-with-msg?
               IllegalArgumentException
               #"Invalid service definition;"
               (parse-and-bootstrap bootstrap-config)))))))

  (testing "comments allowed in bootstrap config file"
    (let [bootstrap-config "./dev-resources/bootstrapping/cli/bootstrap_with_comments.cfg"
          service-maps (->> bootstrap-config
                            parse-bootstrap-config!
                            (map service-map))]
      (is (= (count service-maps) 2))
      (is (contains? (first service-maps) :HelloWorldService))
      (is (contains? (second service-maps) :TestService)))))

(deftest empty-bootstrap
  (testing "Empty bootstrap causes error"
    (testing "single bootstrap file"
      (let [bootstrap-config "./dev-resources/bootstrapping/cli/empty_bootstrap.cfg"]
        (is (thrown-with-msg?
             Exception
             (re-pattern (str "No entries found in any supplied bootstrap file\\(s\\):\n"
                              "./dev-resources/bootstrapping/cli/empty_bootstrap.cfg"))
             (parse-bootstrap-config! bootstrap-config)))))

    (testing "multiple bootstrap files"
      (let [bootstraps ["./dev-resources/bootstrapping/cli/split_bootstraps/empty/empty1.cfg"
                        "./dev-resources/bootstrapping/cli/split_bootstraps/empty/empty2.cfg"]]
        (is (thrown-with-msg?
             Exception
             (re-pattern (str "No entries found in any supplied bootstrap file\\(s\\):\n"
                              (string/join "\n" bootstraps)))
             (parse-bootstrap-configs! bootstraps)))))))

(deftest multiple-bootstrap-files
  (testing "Multiple bootstrap files can be specified directly on the command line"
    (with-test-logging
      (let [bootstrap-one "./dev-resources/bootstrapping/cli/split_bootstraps/one/bootstrap_one.cfg"
            bootstrap-two "./dev-resources/bootstrapping/cli/split_bootstraps/two/bootstrap_two.cfg"
            bootstrap-path (format "%s,%s" bootstrap-one bootstrap-two)
            app (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-path])
            test-svc (get-service app :TestService)
            test-svc-two (get-service app :TestServiceTwo)
            test-svc-three (get-service app :TestServiceThree)
            hello-world-svc (get-service app :HelloWorldService)]
        (is (logged?
             (format "Loading bootstrap configs:\n%s\n%s"
                     bootstrap-one
                     bootstrap-two)
             :debug))
        (is (= (test-fn test-svc) :cli))
        (is (= (test-fn-two test-svc-two) :two))
        (is (= (test-fn-three test-svc-three) :three))
        (is (= (hello-world hello-world-svc) "hello world")))))
  (testing "A path containing multiple .cfg files can be specified on the command line"
    (with-test-logging
      (let [bootstrap-path "./dev-resources/bootstrapping/cli/split_bootstraps/both/"
            app (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-path])
            test-svc (get-service app :TestService)
            test-svc-two (get-service app :TestServiceTwo)
            test-svc-three (get-service app :TestServiceThree)
            hello-world-svc (get-service app :HelloWorldService)]
        (is (logged?
             ; We can't know what order it will find the files on disk, so just
             ; look for a partial match with the path we gave TK.
             (re-pattern (format "Loading bootstrap configs:\n%s"
                                 (fs/absolute bootstrap-path)))
             :debug))
        (is (= (test-fn test-svc) :cli))
        (is (= (test-fn-two test-svc-two) :two))
        (is (= (test-fn-three test-svc-three) :three))
        (is (= (hello-world hello-world-svc) "hello world")))))
  (testing "A path containing both a file and a folder can be specified on the command line"
    (with-test-logging
      (let [bootstrap-one-dir "./dev-resources/bootstrapping/cli/split_bootstraps/one/"
            bootstrap-one "./dev-resources/bootstrapping/cli/split_bootstraps/one/bootstrap_one.cfg"
            bootstrap-two "./dev-resources/bootstrapping/cli/split_bootstraps/two/bootstrap_two.cfg"
            bootstrap-path (format "%s,%s" bootstrap-one-dir bootstrap-two)
            app (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-path])
            test-svc (get-service app :TestService)
            test-svc-two (get-service app :TestServiceTwo)
            test-svc-three (get-service app :TestServiceThree)
            hello-world-svc (get-service app :HelloWorldService)]
        (is (logged?
             (format "Loading bootstrap configs:\n%s\n%s"
                     (fs/absolute bootstrap-one)
                     bootstrap-two)
             :debug))
        (is (= (test-fn test-svc) :cli))
        (is (= (test-fn-two test-svc-two) :two))
        (is (= (test-fn-three test-svc-three) :three))
        (is (= (hello-world hello-world-svc) "hello world"))))))

(deftest bootstrap-path-with-spaces
  (testing "Ensure that a bootstrap config can be loaded with a path that contains spaces"
    (with-test-logging
      (let [bootstrap-path "./dev-resources/bootstrapping/cli/path with spaces/bootstrap.cfg"
            app (bootstrap-with-empty-config
                 ["--bootstrap-config" bootstrap-path])
            test-svc (get-service app :TestService)
            hello-world-svc (get-service app :HelloWorldService)]
        (is (logged?
             (format "Loading bootstrap configs:\n%s" bootstrap-path)
             :debug))
        (is (= (test-fn test-svc) :cli))
        (is (= (hello-world hello-world-svc) "hello world")))))
  (testing "Multiple bootstrap files can be specified with spaces in the names"
    (with-test-logging
      (let [bootstrap-one "./dev-resources/bootstrapping/cli/split_bootstraps/spaces/bootstrap with spaces one.cfg"
            bootstrap-two "./dev-resources/bootstrapping/cli/split_bootstraps/spaces/bootstrap with spaces two.cfg"
            bootstrap-path (format "%s,%s" bootstrap-one bootstrap-two)
            app (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-path])
            test-svc (get-service app :TestService)
            test-svc-two (get-service app :TestServiceTwo)
            test-svc-three (get-service app :TestServiceThree)
            hello-world-svc (get-service app :HelloWorldService)]
        (is (logged?
             (format "Loading bootstrap configs:\n%s\n%s"
                     bootstrap-one
                     bootstrap-two)
             :debug))
        (is (= (test-fn test-svc) :cli))
        (is (= (test-fn-two test-svc-two) :two))
        (is (= (test-fn-three test-svc-three) :three))
        (is (= (hello-world hello-world-svc) "hello world"))))))

(deftest duplicate-service-entries
  (testing "duplicate bootstrap entries are allowed"
    (let [bootstrap-path "./dev-resources/bootstrapping/cli/duplicate_entries.cfg"
          app (bootstrap-with-empty-config
               ["--bootstrap-config" bootstrap-path])
          hello-world-svc (get-service app :HelloWorldService)]
      (is (= (hello-world hello-world-svc) "hello world")))))

(deftest duplicate-service-definitions
  (testing "Duplicate service definitions causes error with filename and line numbers"
    (let [bootstraps ["./dev-resources/bootstrapping/cli/duplicate_services/duplicates.cfg"]]
      (is (thrown-with-msg?
           IllegalArgumentException
           (re-pattern (str "Duplicate implementations found for service protocol ':TestService':\n"
                            ".*/duplicates.cfg:2\n"
                            "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service\n"
                            ".*/duplicates.cfg:3\n"
                            "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service\n"
                            "Duplicate implementations.*\n"
                            ".*/duplicates.cfg:5\n"
                            ".*test-service-two\n"
                            ".*/duplicates.cfg:6\n"
                            ".*test-service-two-duplicate"))
           (parse-bootstrap-configs! bootstraps))))
    (testing "Duplicate service definitions between two files throws error"
      (let [bootstraps ["./dev-resources/bootstrapping/cli/duplicate_services/split_one.cfg"
                        "./dev-resources/bootstrapping/cli/duplicate_services/split_two.cfg"]]
        (is (thrown-with-msg?
             IllegalArgumentException
             (re-pattern (str "Duplicate implementations found for service protocol ':TestService':\n"
                              ".*/split_one.cfg:2\n"
                              "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service\n"
                              ".*/split_two.cfg:2\n"
                              "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service\n"
                              "Duplicate implementations.*\n"
                              ".*/split_one.cfg:4\n"
                              ".*test-service-two-duplicate\n"
                              ".*/split_two.cfg:4\n"
                              ".*test-service-two"))
             (parse-bootstrap-configs! bootstraps)))))))

(deftest config-file-in-jar
  (testing "Bootstrapping via a config file contained in a .jar"
    (let [jar (file "./dev-resources/bootstrapping/jar/this-jar-contains-a-bootstrap-config-file.jar")
          bootstrap-url (str "jar:file:///" (.getAbsolutePath jar) "!/bootstrap.cfg")]
      ;; just test that this bootstrap config file can be read successfully
      ;; (ie, this does not throw an exception)
      (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-url]))))

(deftest parse-bootstrap-config-test
  (testing "Missing service namespace logs warning"
    (with-test-logging
      (let [bootstrap-config "./dev-resources/bootstrapping/cli/fake_namespace_bootstrap.cfg"]
        (parse-bootstrap-config! bootstrap-config)
        (is (logged?
             (str "Unable to load service 'non-existent-service/test-service' from "
                  "./dev-resources/bootstrapping/cli/fake_namespace_bootstrap.cfg:3")
             :warn)))))

  (testing "Missing service definition logs warning"
    (with-test-logging
      (let [bootstrap-config "./dev-resources/bootstrapping/cli/missing_definition_bootstrap.cfg"]
        (parse-bootstrap-config! bootstrap-config)
        (is (logged?
             (str "Unable to load service "
                  "'puppetlabs.trapperkeeper.examples.bootstrapping.test-services/non-existent-service' "
                  "from ./dev-resources/bootstrapping/cli/missing_definition_bootstrap.cfg:3")
             :warn)))))

  (testing "errors are thrown with line number and file"
    ; Load a bootstrap with a bad service graph to generate an error
    (let [bootstrap "./dev-resources/bootstrapping/cli/invalid_service_graph_bootstrap.cfg"]
      (is (thrown-with-msg?
           IllegalArgumentException
           (re-pattern (str "Problem loading service "
                            "'puppetlabs.trapperkeeper.examples.bootstrapping.test-services/invalid-service-graph-service' "
                            "from ./dev-resources/bootstrapping/cli/invalid_service_graph_bootstrap.cfg:1:\n"
                            "Invalid service definition"))
           (parse-bootstrap-config! bootstrap))))))

(deftest get-annotated-bootstrap-entries-test
  (testing "file with comments"
    (let [bootstraps ["./dev-resources/bootstrapping/cli/bootstrap_with_comments.cfg"]]
      (let [entries (get-annotated-bootstrap-entries bootstraps)]
        (is (= [{:bootstrap-file "./dev-resources/bootstrapping/cli/bootstrap_with_comments.cfg"
                 :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service"
                 :line-number 2}
                {:bootstrap-file "./dev-resources/bootstrapping/cli/bootstrap_with_comments.cfg"
                 :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service"
                 :line-number 5}]
               entries)))))
  (testing "multiple bootstrap files"
    (let [bootstraps ["./dev-resources/bootstrapping/cli/split_bootstraps/both/bootstrap_one.cfg"
                      "./dev-resources/bootstrapping/cli/split_bootstraps/both/bootstrap_two.cfg"]]
      (let [entries (get-annotated-bootstrap-entries bootstraps)]
        (is (= [{:bootstrap-file "./dev-resources/bootstrapping/cli/split_bootstraps/both/bootstrap_one.cfg"
                 :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service"
                 :line-number 1}
                {:bootstrap-file "./dev-resources/bootstrapping/cli/split_bootstraps/both/bootstrap_one.cfg"
                 :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service"
                 :line-number 2}
                {:bootstrap-file "./dev-resources/bootstrapping/cli/split_bootstraps/both/bootstrap_two.cfg"
                 :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/test-service-two"
                 :line-number 1}
                {:bootstrap-file "./dev-resources/bootstrapping/cli/split_bootstraps/both/bootstrap_two.cfg"
                 :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/test-service-three"
                 :line-number 2}]
               entries))))))

(deftest find-duplicates-test
  (testing "correct duplicates found"
    (let [items [{:important-key :one
                  :other-key 2}
                 {:important-key :one
                  :other-key 3}
                 {:important-key :two
                  :other-key 4}
                 {:important-key :three
                  :other-key 5}]]
      ; List of key value pairs
      (is (= {:one [{:important-key :one
                     :other-key 2}
                    {:important-key :one
                     :other-key 3}]}
             (find-duplicates items :important-key))))))

(deftest check-duplicate-service-implementations!-test
  (testing "no duplicate service implementations does not throw error"
    (let [configs ["./dev-resources/bootstrapping/cli/bootstrap.cfg"]
          bootstrap-entries (get-annotated-bootstrap-entries configs)
          resolved-services (resolve-services! bootstrap-entries)]
      (check-duplicate-service-implementations! resolved-services bootstrap-entries)))
  (testing "duplicate service implementations throws error"
    (let [configs ["./dev-resources/bootstrapping/cli/duplicate_services/duplicates.cfg"]
          bootstrap-entries (get-annotated-bootstrap-entries configs)
          resolved-services (resolve-services! bootstrap-entries)]
      (is (thrown-with-msg?
           IllegalArgumentException
           #"Duplicate implementations found for service protocol ':TestService'"
           (check-duplicate-service-implementations!
            resolved-services
            bootstrap-entries))))))

(deftest remove-duplicate-entries-test
  (testing "single bootstrap with all duplicates"
    (testing "only the first duplicate found is kept"
      (let [configs ["./dev-resources/bootstrapping/cli/duplicate_entries.cfg"]
            bootstrap-entries (get-annotated-bootstrap-entries configs)]
        (is (= [{:bootstrap-file "./dev-resources/bootstrapping/cli/duplicate_entries.cfg"
                 :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service"
                 :line-number 1}]
               (remove-duplicate-entries bootstrap-entries))))))
  (testing "two copies of the same set of services"
    (let [configs ["./dev-resources/bootstrapping/cli/bootstrap.cfg"
                   "./dev-resources/bootstrapping/cli/bootstrap.cfg"]
          bootstrap-entries (get-annotated-bootstrap-entries configs)]
      (is (= [{:bootstrap-file "./dev-resources/bootstrapping/cli/bootstrap.cfg"
               :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service"
               :line-number 1}
              {:bootstrap-file "./dev-resources/bootstrapping/cli/bootstrap.cfg"
               :entry "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service"
               :line-number 2}]
             (remove-duplicate-entries bootstrap-entries))))))

(deftest read-config-test
  (testing "basic config"
    (let [config "./dev-resources/bootstrapping/cli/bootstrap.cfg"]
      (is (= ["puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service"
              "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service"]
             (read-config config)))))
  (testing "jar uri"
    (let [jar "./dev-resources/bootstrapping/jar/this-jar-contains-a-bootstrap-config-file.jar"
          config (str "jar:file:///" (.getAbsolutePath (file jar)) "!/bootstrap.cfg")]
      ; The bootstrap in the jar contains an empty line at the end
      (is (= ["puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service"
              ""]
             (read-config config)))))
  (testing "malformed uri is wrapped in our exception"
    (let [config "\n"]
      (is (thrown-with-msg?
           IllegalArgumentException
           #"Specified bootstrap config file does not exist"
           (read-config config)))))
  (testing "Non-absolute uri is wrapped in our exception"
    ; TODO This path is currently interpreted as a URI because TK checks
    ; if it's a file, and if not, attemps to load as a URI
    (let [config "./not-a-file"]
      (is (thrown-with-msg?
           IllegalArgumentException
           #"Specified bootstrap config file does not exist"
           (println (read-config config))))))
  (testing "Non-existent file in URI is wrapped in our exception"
    (let [config "file:///not-a-file"]
      (is (thrown-with-msg?
           IllegalArgumentException
           #"Specified bootstrap config file does not exist"
           (read-config config))))))
