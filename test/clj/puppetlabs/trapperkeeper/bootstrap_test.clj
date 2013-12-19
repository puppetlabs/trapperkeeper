(ns puppetlabs.trapperkeeper.bootstrap-test
  (:import (java.io StringReader))
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [file]]
            [puppetlabs.kitchensink.classpath :refer [with-additional-classpath-entries]]
            [puppetlabs.trapperkeeper.services :refer [get-service-fn]]
            [puppetlabs.trapperkeeper.bootstrap :refer [parse-bootstrap-config!]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-with-empty-config parse-and-bootstrap]]))

(deftest bootstrapping
  (testing "Valid bootstrap configurations"
    (let [bootstrap-config    "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service

"
          app                 (parse-and-bootstrap (StringReader. bootstrap-config))]

      (testing "Can load a service based on a valid bootstrap config string"
        (let [test-fn             (get-service-fn app :foo-test-service :test-fn)
              hello-world-fn      (get-service-fn app :hello-world-service :hello-world)]
          (is (= (test-fn) :foo))
          (is (= (hello-world-fn) "hello world"))))

    (with-additional-classpath-entries ["./test-resources/bootstrapping/classpath"]
      (testing "Looks for bootstrap config on classpath (test-resources)"
        (with-test-logging
          (let [app                 (bootstrap-with-empty-config)
                test-fn             (get-service-fn app :classpath-test-service :test-fn)
                hello-world-fn      (get-service-fn app :hello-world-service :hello-world)]
            (is (logged?
                  #"Loading bootstrap config from classpath: 'file:/.*test-resources/bootstrapping/classpath/bootstrap.cfg'"
                  :debug))
            (is (= (test-fn) :classpath))
            (is (= (hello-world-fn) "hello world")))))

      (testing "Gives precedence to bootstrap config in cwd"
        (let [old-cwd (System/getProperty "user.dir")]
          (try
            (System/setProperty
              "user.dir"
              (.getAbsolutePath (file "./test-resources/bootstrapping/cwd")))
            (with-test-logging
              (let [app                 (bootstrap-with-empty-config)
                    test-fn             (get-service-fn app :cwd-test-service :test-fn)
                    hello-world-fn      (get-service-fn app :hello-world-service :hello-world)]
                (is (logged?
                      #"Loading bootstrap config from current working directory: '.*/test-resources/bootstrapping/cwd/bootstrap.cfg'"
                      :debug))
                (is (= (test-fn) :cwd))
                (is (= (hello-world-fn) "hello world"))))
            (finally (System/setProperty "user.dir" old-cwd)))))

      (testing "Gives precedence to bootstrap config specified as CLI arg"
        (with-test-logging
            (let [app                 (bootstrap-with-empty-config ["--bootstrap-config" "./test-resources/bootstrapping/cli/bootstrap.cfg"])
                  test-fn             (get-service-fn app :cli-test-service :test-fn)
                  hello-world-fn      (get-service-fn app :hello-world-service :hello-world)]
              (is (logged?
                    #"Loading bootstrap config from specified path: './test-resources/bootstrapping/cli/bootstrap.cfg'"
                    :debug))
              (is (= (test-fn) :cli))
              (is (= (hello-world-fn) "hello world")))))

      (testing "Ensure that a bootstrap config can be loaded with a path that contains spaces"
        (with-test-logging
          (let [app                 (bootstrap-with-empty-config ["--bootstrap-config" "./test-resources/bootstrapping/cli/path with spaces/bootstrap.cfg"])
                test-fn             (get-service-fn app :cli-test-service :test-fn)
                hello-world-fn      (get-service-fn app :hello-world-service :hello-world)]
            (is (logged?
                  #"Loading bootstrap config from specified path: './test-resources/bootstrapping/cli/path with spaces/bootstrap.cfg'"
                  :debug))
            (is (= (test-fn) :cli))
            (is (= (hello-world-fn) "hello world")))))))

  (testing "Invalid bootstrap configurations"
    (testing "Bootstrap config path specified on CLI does not exist"
      (let [cfg-path "./test-resources/bootstrapping/cli/non-existent-bootstrap.cfg"]
        (is (thrown-with-msg?
              IllegalArgumentException
              #"Specified bootstrap config file does not exist: '.*non-existent-bootstrap.cfg'"
              (bootstrap-with-empty-config ["--bootstrap-config" cfg-path])))))

    (testing "No bootstrap config found"
      (is (thrown-with-msg?
            IllegalStateException
            #"Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath"
            (bootstrap-with-empty-config)))
      (is (thrown-with-msg?
            IllegalStateException
            #"Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath"
            (bootstrap-with-empty-config ["--bootstrap-config" nil]))))

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
              #"Invalid service graph;"
              (parse-and-bootstrap bootstrap-config)))))))

  (testing "comments allowed in bootstrap config file"
    (let [bootstrap-config "
 # commented out line
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service # comment
; another commented out line
 ;puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service ; comment"
          services        (parse-bootstrap-config! (StringReader. bootstrap-config))]
      (is (= (count services) 2))
      (is (contains? (first services) :hello-world-service))
      (is (contains? (second services) :foo-test-service)))))
