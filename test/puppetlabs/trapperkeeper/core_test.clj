(ns puppetlabs.trapperkeeper.core_test
  (:import (java.io StringReader))
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.java.io :refer [file]]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.testutils.logging :refer [with-test-logging with-test-logging-debug]]
            [puppetlabs.utils.classpath :refer [with-additional-classpath-entries]]))

(deftest test-bootstrapping
  (testing "Valid bootstrap configurations"
    (let [bootstrap-config    "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service

"
          app                 (trapperkeeper/bootstrap* (StringReader. bootstrap-config))]

      (testing "Can load a service based on a valid bootstrap config string"
        (let [test-fn             (trapperkeeper/get-service-fn app :test-service :test-fn)
              hello-world-fn      (trapperkeeper/get-service-fn app :hello-world-service :hello-world)]
          (is (= (test-fn) :foo))
          (is (= (hello-world-fn) "hello world"))))

      (testing "The CLI service is included in the service graph."
        (let [cli-data-fn (trapperkeeper/get-service-fn app :cli-service :cli-data)]
          (is (= (cli-data-fn) {}))))

      (testing "The CLI service has the CLI data."
        (let [config-file-path  "/path/to/config/files"
              cli-data          (trapperkeeper/parse-cli-args! ["--config" config-file-path "--debug"])
              app-with-cli-args (trapperkeeper/bootstrap* (StringReader. bootstrap-config) cli-data)
              cli-data-fn       (trapperkeeper/get-service-fn app-with-cli-args :cli-service :cli-data)]
          (is (not (empty? (cli-data-fn))))
          (is (cli-data-fn :debug))
          (is (= config-file-path (cli-data-fn :config))))))

    (with-additional-classpath-entries ["./test-resources/bootstrapping/classpath"]
      (testing "Looks for bootstrap config on classpath (test-resources)"
        (with-test-logging
          (let [app                 (trapperkeeper/bootstrap [])
                test-fn             (trapperkeeper/get-service-fn app :test-service :test-fn)
                hello-world-fn      (trapperkeeper/get-service-fn app :hello-world-service :hello-world)]
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
              (let [app                 (trapperkeeper/bootstrap [])
                    test-fn             (trapperkeeper/get-service-fn app :test-service :test-fn)
                    hello-world-fn      (trapperkeeper/get-service-fn app :hello-world-service :hello-world)]
                (is (logged?
                      #"Loading bootstrap config from current working directory: '.*/test-resources/bootstrapping/cwd/bootstrap.cfg'"
                      :debug))
                (is (= (test-fn) :cwd))
                (is (= (hello-world-fn) "hello world"))))
            (finally (System/setProperty "user.dir" old-cwd)))))

      (testing "Gives precedence to bootstrap config specified as CLI arg"
        (with-test-logging
            (let [app                 (trapperkeeper/bootstrap ["--bootstrap-config" "./test-resources/bootstrapping/cli/bootstrap.cfg"])
                  test-fn             (trapperkeeper/get-service-fn app :test-service :test-fn)
                  hello-world-fn      (trapperkeeper/get-service-fn app :hello-world-service :hello-world)]
              (is (logged?
                    #"Loading bootstrap config from specified path: './test-resources/bootstrapping/cli/bootstrap.cfg'"
                    :debug))
              (is (= (test-fn) :cli))
              (is (= (hello-world-fn) "hello world")))))))

  (testing "Invalid bootstrap configurations"
    (testing "Bootstrap config path specified on CLI does not exist"
      (let [cfg-path "./test-resources/bootstrapping/cli/non-existent-bootstrap.cfg"]
        (is (thrown-with-msg?
              IllegalArgumentException
              #"Specified bootstrap config file does not exist: '.*non-existent-bootstrap.cfg'"
              (trapperkeeper/bootstrap
                ["--bootstrap-config" cfg-path])))))

    (testing "No bootstrap config found"
      (is (thrown-with-msg?
            IllegalStateException
            #"Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath"
            (trapperkeeper/bootstrap []))))

    (testing "Bad line in bootstrap config file"
      (let [bootstrap-config (StringReader. "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
This is not a legit line.
")]
        (is (thrown-with-msg?
              IllegalArgumentException
              #"(?is)Invalid line in bootstrap.*This is not a legit line"
              (trapperkeeper/bootstrap* bootstrap-config)))))

    (testing "Bootstrap config file is empty."
      (let [bootstrap-config (StringReader. "")]
        (is (thrown-with-msg?
              Exception
              #"Empty bootstrap config file"
        (trapperkeeper/bootstrap* bootstrap-config)))))

    (testing "Service namespace doesn't exist"
      (let [bootstrap-config (StringReader.
                               "non-existent-service/test-service")]
        (is (thrown-with-msg?
              IllegalArgumentException
              #"Unable to load service: non-existent-service/test-service"
              (trapperkeeper/bootstrap* bootstrap-config)))))

    (testing "Service function doesn't exist"
      (let [bootstrap-config (StringReader.
                               "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/non-existent-service")]
        (is (thrown-with-msg?
              IllegalArgumentException
              #"Unable to load service: puppetlabs.trapperkeeper.examples.bootstrapping.test-services/non-existent-service"
              (trapperkeeper/bootstrap* bootstrap-config)))))

    (testing "Invalid service graph"

      (let [bootstrap-config (StringReader.
                               "puppetlabs.trapperkeeper.examples.bootstrapping.test-services/invalid-service-graph-service")]
        (is (thrown-with-msg?
              IllegalArgumentException
              #"Invalid service graph;"
              (trapperkeeper/bootstrap* bootstrap-config))))))

  (testing "CLI arg parsing"
    (testing "CLI args are parsed to a map"
      (is (map? (trapperkeeper/parse-cli-args! [])))
      (is (map? (trapperkeeper/parse-cli-args! ["--debug"]))))

    (testing "Parsed CLI data"
      (is (contains? (trapperkeeper/parse-cli-args! ["--debug"]) :debug))
      (let [bootstrap-file "/fake/path/bootstrap.cfg"
            config-dir     "/fake/config/dir"
            cli-data         (trapperkeeper/parse-cli-args!
                               ["--debug"
                                "--bootstrap-config" bootstrap-file
                                "--config" config-dir])]
        (is (= bootstrap-file (cli-data :bootstrap-config)))
        (is (= config-dir (cli-data :config)))
        (is (cli-data :debug)))))

    (testing "Invalid CLI data"
      (is (thrown-with-msg?
            Exception
            #"not a valid argument"
            (trapperkeeper/parse-cli-args! ["--invalid-argument"])))))
