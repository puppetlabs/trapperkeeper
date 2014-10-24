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
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-with-empty-config parse-and-bootstrap]]
            [puppetlabs.trapperkeeper.examples.bootstrapping.test-services :refer [test-fn hello-world]]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

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
            (let [app             (bootstrap-with-empty-config ["--bootstrap-config" "./dev-resources/bootstrapping/cli/bootstrap.cfg"])
                  test-svc        (get-service app :TestService)
                  hello-world-svc (get-service app :HelloWorldService)]
              (is (logged?
                    #"Loading bootstrap config from specified path: './dev-resources/bootstrapping/cli/bootstrap.cfg'"
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

(deftest bootstrap-path-with-spaces
  (testing "Ensure that a bootstrap config can be loaded with a path that contains spaces"
    (with-test-logging
      (let [app             (bootstrap-with-empty-config
                               ["--bootstrap-config" "./dev-resources/bootstrapping/cli/path with spaces/bootstrap.cfg"])
            test-svc        (get-service app :TestService)
            hello-world-svc (get-service app :HelloWorldService)]
        (is (logged?
              #"Loading bootstrap config from specified path: './dev-resources/bootstrapping/cli/path with spaces/bootstrap.cfg'"
              :debug))
        (is (= (test-fn test-svc) :cli))
        (is (= (hello-world hello-world-svc) "hello world"))))))

(deftest config-file-in-jar
  (testing "Bootstrapping via a config file contained in a .jar"
    (let [jar           (file "./dev-resources/bootstrapping/jar/this-jar-contains-a-bootstrap-config-file.jar")
          bootstrap-url (str "jar:file:///" (.getAbsolutePath jar) "!/bootstrap.cfg")]
      ;; just test that this bootstrap config file can be read successfully
      ;; (ie, this does not throw an exception)
      (bootstrap-with-empty-config ["--bootstrap-config" bootstrap-url]))))
