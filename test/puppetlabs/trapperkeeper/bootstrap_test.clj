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
            [me.raynes.fs :as fs]))

(use-fixtures :once
  schema-test/validate-schemas
  ;; Without this, "lein test NAMESPACE" and :only invocations may fail.
  (fn [f] (reset-logging) (f)))

(deftest bootstrapping
  (testing "Valid bootstrap configurations"
    (let [bootstrap-config    "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service

"
          app                 (parse-and-bootstrap (StringReader. bootstrap-config))]

      (testing "Can load a service based on a valid bootstrap config string"
        (let [test-svc        (get-service app :TestService)
              hello-world-svc (get-service app :HelloWorldService)]
          (is (= (test-fn test-svc) :foo))
          (is (= (hello-world hello-world-svc) "hello world"))))

      (with-additional-classpath-entries ["./dev-resources/bootstrapping/classpath"]
        (testing "Looks for bootstrap config on classpath (dev-resources)"
          (with-test-logging
            (let [app             (bootstrap-with-empty-config)
                  test-svc        (get-service app :TestService)
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
                (let [app             (bootstrap-with-empty-config)
                      test-svc        (get-service app :TestService)
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
                   (format "Loading bootstrap configs:\n%s" (fs/absolute bootstrap-path))
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
        (let [bootstrap-config (StringReader. "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
This is not a legit line.
")]
          (is (thrown-with-msg?
               IllegalArgumentException
               #"(?is)Invalid line in bootstrap.*This is not a legit line"
               (parse-and-bootstrap bootstrap-config)))))

      (testing "Bootstrap config file is empty."
        (let [bootstrap-config (StringReader. "")]
          (is (thrown-with-msg?
               Exception
               #"Empty bootstrap config file"
               (parse-and-bootstrap bootstrap-config)))))

      (testing "Service namespace doesn't exist"
        (let [bootstrap-config (StringReader.
                                "non-existent-service/test-service")]
          (is (thrown-with-msg?
               IllegalArgumentException
               #"Unable to load service: non-existent-service/test-service"
               (parse-and-bootstrap bootstrap-config)))))

      (testing "Service function doesn't exist"
        (let [bootstrap-config (StringReader.
                                "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/non-existent-service")]
          (is (thrown-with-msg?
               IllegalArgumentException
               #"Unable to load service: puppetlabs.trapperkeeper.examples.bootstrapping.test-services/non-existent-service"
               (parse-and-bootstrap bootstrap-config)))))

      (testing "Invalid service graph"

        (let [bootstrap-config (StringReader.
                                "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/invalid-service-graph-service")]
          (is (thrown-with-msg?
               IllegalArgumentException
               #"Invalid service definition;"
               (parse-and-bootstrap bootstrap-config)))))))

  (testing "comments allowed in bootstrap config file"
    (let [bootstrap-config "
 # commented out line
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service # comment
; another commented out line
 ;puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service ; comment"
          service-maps      (->> bootstrap-config
                                 (StringReader.)
                                 parse-bootstrap-config!
                                 (map service-map))]
      (is (= (count service-maps) 2))
      (is (contains? (first service-maps) :HelloWorldService))
      (is (contains? (second service-maps) :TestService)))))

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
                     (fs/absolute bootstrap-one)
                     (fs/absolute bootstrap-two))
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
                     (fs/absolute bootstrap-two))
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
             (format "Loading bootstrap configs:\n%s" (fs/absolute bootstrap-path))
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
                     (fs/absolute bootstrap-one)
                     (fs/absolute bootstrap-two))
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
    (let [bootstrap "./dev-resources/bootstrapping/cli/duplicate_services.cfg"]
      (is (thrown-with-msg?
            IllegalArgumentException
            (re-pattern (str "Duplicate implementations found for service protocol ':TestService':\n"
                             ".*/duplicate_services.cfg:2\n"
                             "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service\n"
                             ".*/duplicate_services.cfg:3\n"
                             "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service"))
            (parse-bootstrap-config! bootstrap))))))

(deftest config-file-in-jar
  (testing "Bootstrapping via a config file contained in a .jar"
    (let [jar           (file "./dev-resources/bootstrapping/jar/this-jar-contains-a-bootstrap-config-file.jar")
          bootstrap-url (str "jar:file:///" (.getAbsolutePath jar) "!/bootstrap.cfg")]
      ;; just test that this bootstrap config file can be read successfully
      ;; (ie, this does not throw an exception)
      (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-url]))))

(deftest parse-bootstrap-config-throws
  (testing "throws error with line number and file"
    (let [bootstrap (file "./dev-resources/bootstrapping/cli/invalid_bootstrap.cfg")]
      (is (thrown-with-msg?
            IllegalArgumentException
            (re-pattern (str "Problem loading service 'fake.notreal/wont-find-me' "
                             "on line '3' in bootstrap configuration file "
                             "'./dev-resources/bootstrapping/cli/invalid_bootstrap.cfg'"
                             ":\nUnable to load service: fake.notreal/wont-find-me"))
            (parse-bootstrap-config! bootstrap))))))


