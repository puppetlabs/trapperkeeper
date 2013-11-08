(ns puppetlabs.trapperkeeper.core-test
  (:import (java.io StringReader))
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.java.io :refer [file]]
            [plumbing.fnk.pfnk :as pfnk]
            [plumbing.graph :as graph]
            [puppetlabs.trapperkeeper.bootstrap :refer [parse-bootstrap-config!]]
            [puppetlabs.trapperkeeper.core :as trapperkeeper :refer [defservice service]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging with-test-logging-debug]]
            [puppetlabs.utils.classpath :refer [with-additional-classpath-entries]]))

(defn- parse-and-bootstrap
  ([bootstrap-config]
   (parse-and-bootstrap bootstrap-config {}))
  ([bootstrap-config cli-data]
   (-> bootstrap-config
       parse-bootstrap-config!
       (trapperkeeper/bootstrap* cli-data))))

(deftest test-bootstrapping
  (testing "Valid bootstrap configurations"
    (let [bootstrap-config    "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/foo-test-service
puppetlabs.trapperkeeper.examples.bootstrapping.test-services/hello-world-service

"
          app                 (parse-and-bootstrap (StringReader. bootstrap-config))]

      (testing "Can load a service based on a valid bootstrap config string"
        (let [test-fn             (trapperkeeper/get-service-fn app :foo-test-service :test-fn)
              hello-world-fn      (trapperkeeper/get-service-fn app :hello-world-service :hello-world)]
          (is (= (test-fn) :foo))
          (is (= (hello-world-fn) "hello world"))))

      (testing "The CLI service is included in the service graph."
        (let [cli-data-fn (trapperkeeper/get-service-fn app :cli-service :cli-data)]
          (is (= (cli-data-fn) {}))))

      (testing "The CLI service has the CLI data."
        (let [config-file-path  "/path/to/config/files"
              cli-data          (trapperkeeper/parse-cli-args! ["--config" config-file-path "--debug"])
              app-with-cli-args (parse-and-bootstrap (StringReader. bootstrap-config) cli-data)
              cli-data-fn       (trapperkeeper/get-service-fn app-with-cli-args :cli-service :cli-data)]
          (is (not (empty? (cli-data-fn))))
          (is (cli-data-fn :debug))
          (is (= config-file-path (cli-data-fn :config))))))

    (with-additional-classpath-entries ["./test-resources/bootstrapping/classpath"]
      (testing "Looks for bootstrap config on classpath (test-resources)"
        (with-test-logging
          (let [app                 (trapperkeeper/bootstrap [])
                test-fn             (trapperkeeper/get-service-fn app :classpath-test-service :test-fn)
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
                    test-fn             (trapperkeeper/get-service-fn app :cwd-test-service :test-fn)
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
                  test-fn             (trapperkeeper/get-service-fn app :cli-test-service :test-fn)
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
              (parse-and-bootstrap bootstrap-config))))))

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

(deftest defservice-macro
  (def logging-service
    (service logging-service
      {:depends  []
       :provides [log]}
      {:log (fn [msg] "do nothing")}))

  (defservice simple-service
    "My simple service"
    {:depends  [[:logging-service log]]
     :provides [hello]}
    ;; this line is just here to test support for multi-form service bodies
    (log "Calling our test log function!")
    (let [state "world"]
      {:hello (fn [] state)}))

  (testing "service has the correct form"
    (is (= (:doc (meta #'simple-service)) "My simple service"))

    (let [service-graph (simple-service)]
      (is (map? service-graph))

      (let [graph-keys (keys service-graph)]
        (is (= (count graph-keys) 1))
        (is (= (first graph-keys) :simple-service)))

      (let [service-fnk  (:simple-service service-graph)
            depends      (pfnk/input-schema service-fnk)
            provides     (pfnk/output-schema service-fnk)]
        (is (ifn? service-fnk))
        (is (= depends  {:logging-service {:log true}}))
        (is (= provides {:hello true})))))

  (testing "services compile correctly and can be called"
    (let [app       (trapperkeeper/bootstrap* [(logging-service) (simple-service)])
          hello-fn  (trapperkeeper/get-service-fn app :simple-service :hello)]
      (is (= (hello-fn) "world")))))

(deftest shutdown
  (testing "service with shutdown hook gets called during shutdown"
    (let [flag          (atom false)
          test-service  (service test-service
                                 {:depends  []
                                  :provides [shutdown]}
                                 {:shutdown #(reset! flag true)})
          app           (trapperkeeper/bootstrap* [(test-service)])]
      (is (false? @flag))
      (trapperkeeper/shutdown! app)
      (is (true? @flag))))

  (testing "services are shut down in reverse dependency order"
    (let [order       (atom [])
          service1    (service service1
                               {:depends  []
                                :provides [shutdown]}
                               {:shutdown #(swap! order conj 1)})
          service2    (service service2
                               {:depends  [service1]
                                :provides [shutdown]}
                               {:shutdown #(swap! order conj 2)})
          app         (trapperkeeper/bootstrap* [(service1) (service2)])]
      (is (empty? @order))
      (trapperkeeper/shutdown! app)
      (is (= @order [2 1])))))
