(ns puppetlabs.trapperkeeper.core-test
  (:import (java.io StringReader))
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.java.io :refer [file]]
            [plumbing.fnk.pfnk :as pfnk]
            [plumbing.graph :as graph]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.trapperkeeper.bootstrap :refer [parse-bootstrap-config!]]
            [puppetlabs.trapperkeeper.core :as trapperkeeper :refer [defservice service parse-cli-args!]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging with-test-logging-debug]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
            [puppetlabs.kitchensink.classpath :refer [with-additional-classpath-entries]]
            [puppetlabs.kitchensink.testutils.fixtures :refer [with-no-jvm-shutdown-hooks]]))

(use-fixtures :once with-no-jvm-shutdown-hooks)

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
          (is (not (nil? (cli-data-fn))))))

    (with-additional-classpath-entries ["./test-resources/bootstrapping/classpath"]
      (testing "Looks for bootstrap config on classpath (test-resources)"
        (with-test-logging
          (let [app                 (bootstrap-with-empty-config)
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
              (let [app                 (bootstrap-with-empty-config)
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
            (let [app                 (bootstrap-with-empty-config ["--bootstrap-config" "./test-resources/bootstrapping/cli/bootstrap.cfg"])
                  test-fn             (trapperkeeper/get-service-fn app :cli-test-service :test-fn)
                  hello-world-fn      (trapperkeeper/get-service-fn app :hello-world-service :hello-world)]
              (is (logged?
                    #"Loading bootstrap config from specified path: './test-resources/bootstrapping/cli/bootstrap.cfg'"
                    :debug))
              (is (= (test-fn) :cli))
              (is (= (hello-world-fn) "hello world")))))

      (testing "Ensure that a bootstrap config can be loaded with a path that contains spaces"
        (with-test-logging
          (let [app                 (bootstrap-with-empty-config ["--bootstrap-config" "./test-resources/bootstrapping/cli/path with spaces/bootstrap.cfg"])
                test-fn             (trapperkeeper/get-service-fn app :cli-test-service :test-fn)
                hello-world-fn      (trapperkeeper/get-service-fn app :hello-world-service :hello-world)]
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
              (parse-and-bootstrap bootstrap-config))))))))

(deftest defservice-macro
  (def logging-service
    (service :logging-service
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
    (let [app       (bootstrap-services-with-empty-config [(logging-service) (simple-service)])
          hello-fn  (trapperkeeper/get-service-fn app :simple-service :hello)]
      (is (= (hello-fn) "world")))))

(deftest shutdown
  (testing "service with shutdown hook gets called during shutdown"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  []
                                      :provides [shutdown]}
                                     {:shutdown #(reset! shutdown-called? true)})
          app               (bootstrap-services-with-empty-config [(test-service)])]
      (is (false? @shutdown-called?))
      (trapperkeeper/shutdown!)
      (is (true? @shutdown-called?))))

  (testing "services are shut down in dependency order"
    (let [order       (atom [])
          service1    (service :service1
                               {:depends  []
                                :provides [shutdown]}
                               {:shutdown #(swap! order conj 1)})
          service2    (service :service2
                               {:depends  [service1]
                                :provides [shutdown]}
                               {:shutdown #(swap! order conj 2)})
          app         (bootstrap-services-with-empty-config [(service1) (service2)])]
      (is (empty? @order))
      (trapperkeeper/shutdown!)
      (is (= @order [2 1]))))

  (testing "services continue to shut down when one throws an exception"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  []
                                      :provides [shutdown]}
                                     {:shutdown #(reset! shutdown-called? true)})
          broken-service    (service :broken-service
                                     {:depends  [test-service]
                                      :provides [shutdown]}
                                     {:shutdown #(throw (RuntimeException. "dangit"))})
          app               (bootstrap-services-with-empty-config [(test-service) (broken-service)])]
      (is (false? @shutdown-called?))
      (with-test-logging
        (trapperkeeper/shutdown!)
        (is (logged? #"Encountered error during shutdown sequence" :error)))
      (is (true? @shutdown-called?))))

  (testing "`run` blocks until shutdown signal received, then services are shut down"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  []
                                      :provides [shutdown]}
                                     {:shutdown #(reset! shutdown-called? true)})
          app               (bootstrap-services-with-empty-config [(test-service)])
          thread            (future (trapperkeeper/runApp app))]
      (is (false? @shutdown-called?))
      (trapperkeeper/request-shutdown! app)
      (deref thread)
      (is (true? @shutdown-called?))))

  (testing "`shutdown-on-error` causes services to be shut down and the error is rethrown from main"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  [[:shutdown-service shutdown-on-error]]
                                      :provides [broken-fn shutdown]}
                                     {:shutdown  #(reset! shutdown-called? true)
                                      :broken-fn (fn [] (future (shutdown-on-error #(throw (RuntimeException. "oops")))))})
          app                (bootstrap-services-with-empty-config [(test-service)])
          broken-fn          (trapperkeeper/get-service-fn app :test-service :broken-fn)
          main-thread        (future (trapperkeeper/runApp app))]
      (is (false? @shutdown-called?))
      (broken-fn)
      (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"java.lang.RuntimeException: oops"
            (deref main-thread)))
      (is (true? @shutdown-called?))))

  (testing "`shutdown-on-error` takes an optional function that is called on error"
    (let [shutdown-called?    (atom false)
          on-error-fn-called? (atom false)
          broken-service      (service :broken-service
                                       {:depends  [[:shutdown-service shutdown-on-error]]
                                        :provides [broken-fn shutdown]}
                                       {:shutdown  #(reset! shutdown-called? true)
                                        :broken-fn (fn [] (shutdown-on-error #(throw (RuntimeException. "uh oh"))
                                                                             #(reset! on-error-fn-called? true)))})
          app                 (bootstrap-services-with-empty-config [(broken-service)])
          broken-fn           (trapperkeeper/get-service-fn app :broken-service :broken-fn)
          main-thread         (future (trapperkeeper/runApp app))]
      (is (false? @shutdown-called?))
      (is (false? @on-error-fn-called?))
      (broken-fn)
      (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"java.lang.RuntimeException: uh oh"
            (deref main-thread)))
      (is (true? @shutdown-called?))
      (is (true? @on-error-fn-called?))))

  (testing "errors thrown by the `shutdown-on-error` optional on-error function are caught and logged"
    (let [broken-service  (service :broken-service
                                   {:depends  [[:shutdown-service shutdown-on-error]]
                                    :provides [broken-fn]}
                                   {:broken-fn (fn [] (shutdown-on-error #(throw (RuntimeException. "unused"))
                                                                         #(throw (RuntimeException. "catch me"))))})
          app             (bootstrap-services-with-empty-config [(broken-service)])
          broken-fn       (trapperkeeper/get-service-fn app :broken-service :broken-fn)]
      (with-test-logging
        (let [main-thread (future (trapperkeeper/runApp app))]
          (broken-fn)
          ;; main will rethrow the "unused" exception as expected
          ;; so we need to prevent that from failing the test
          (try (deref main-thread) (catch Throwable t))
          (is (logged? #"Error occurred during shutdown" :error)))))))

(deftest dependency-error-handling
  (testing "missing service dependency throws meaningful message"
    (let [broken-service (service :broken-service
                                  {:depends  [[:missing-service f]]
                                   :provides [unused]}
                                  {:unused #()})]
      (is (thrown-with-msg?
            RuntimeException #"Service ':missing-service' not found"
            (bootstrap-services-with-empty-config [(broken-service)])))))

  (testing "missing service function throws meaningful message"
    (let [test-service    (service :test-service
                                   {:depends  []
                                    :provides [foo]}
                                   {:foo #()})
          broken-service  (service :broken-service
                                   {:depends  [[:test-service bar]]
                                    :provides [unused]}
                                   {:unused #()})]
      (is (thrown-with-msg?
            RuntimeException #"Service function 'bar' not found"
            (bootstrap-services-with-empty-config [(test-service) (broken-service)]))))

    (let [test-service    (service :test-service
                                   {:depends  []
                                    :provides [foo bar]}
                                   {:foo #()})
          broken-service  (service :broken-service
                                   {:depends  [[:test-service foo bar]]
                                    :provides [unused]}
                                   {:unused #()})]
      (is (thrown-with-msg?
            RuntimeException #"Service function 'bar' not found"
            (bootstrap-services-with-empty-config [(test-service) (broken-service)]))))

    (let [broken-service  (service :broken-service
                                   {:depends  []
                                    :provides [unused]}
                                   (throw (RuntimeException. "This shouldn't match the regexs"))
                                   {:unused #()})]
      (is (thrown-with-msg?
            RuntimeException #"This shouldn't match the regexs"
            (bootstrap-services-with-empty-config [(broken-service)]))))))

(deftest test-main
  (testing "Parsed CLI data"
    (let [bootstrap-file "/fake/path/bootstrap.cfg"
          config-dir     "/fake/config/dir"
          cli-data         (parse-cli-args!
                             ["--debug"
                              "--bootstrap-config" bootstrap-file
                              "--config" config-dir])]
      (is (= bootstrap-file (cli-data :bootstrap-config)))
      (is (= config-dir (cli-data :config)))
      (is (cli-data :debug))))

  (testing "Invalid CLI data"
    (is (thrown-with-msg?
          Exception
          #"not a valid argument"
          (parse-cli-args! ["--invalid-argument"]))))

  (testing "The CLI service has the CLI data."
    (let [bootstrap-config "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service

"
          config-file-path  "./test-resources/config/empty.ini"
          cli-data          (parse-cli-args! ["--config" config-file-path "--debug"])
          app-with-cli-args (parse-and-bootstrap (StringReader. bootstrap-config) cli-data)
          cli-data-fn       (trapperkeeper/get-service-fn app-with-cli-args :cli-service :cli-data)]
      (is (not (empty? (cli-data-fn))))
      (is (cli-data-fn :debug))
      (is (= config-file-path (cli-data-fn :config)))))

  (testing "Fails if config CLI arg is not specified"
    ;; looks like `thrown-with-msg?` can't be used with slingshot. :(
    (let [got-expected-exception (atom false)]
      (try+
        (parse-cli-args! [])
        (catch map? m
          (is (contains? m :error-message))
          (is (re-matches
                #"(?s)^.*Missing required argument '--config'.*$"
                (m :error-message)))
          (reset! got-expected-exception true)))
      (is (true? @got-expected-exception)))))
