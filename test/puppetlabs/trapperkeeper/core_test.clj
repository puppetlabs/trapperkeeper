(ns puppetlabs.trapperkeeper.core-test
  (:require [clojure.test :refer :all]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :refer [without-ns]]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.internal :refer [get-service parse-cli-args!]]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.trapperkeeper.testutils.logging :refer [reset-logging-config-after-test]]))

(use-fixtures :each reset-logging-config-after-test)

(defprotocol FooService
  (foo [this]))

(deftest dependency-error-handling
  (testing "missing service dependency throws meaningful message"
    (let [broken-service (service
                           [[:MissingService f]]
                           (init [this context] (f) context))]
      (is (thrown-with-msg?
            RuntimeException #"Service/function ':MissingService' not found"
            (bootstrap-services-with-empty-config [broken-service])))))

  (testing "missing service function throws meaningful message"
    (let [test-service    (service FooService
                                   []
                                   (foo [this] "foo"))
          broken-service  (service
                            [[:FooService bar]]
                            (init [this context] (bar) context))]
      (is (thrown-with-msg?
            RuntimeException #"Service function 'bar' not found"
            (bootstrap-services-with-empty-config [test-service broken-service]))))

    (let [broken-service  (service
                            []
                            (init [this context]
                                  (throw (RuntimeException. "This shouldn't match the regexs"))))]
      (is (thrown-with-msg?
            RuntimeException #"This shouldn't match the regexs"
            (bootstrap-services-with-empty-config [broken-service]))))

    (is (thrown-with-msg?
          RuntimeException #"Service does not define function 'foo'"
          (macroexpand '(puppetlabs.trapperkeeper.services/service
                          puppetlabs.trapperkeeper.core-test/FooService
                          []
                          (init [this context] context)))))))

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
    (let [got-expected-exception (atom false)]
      (try+
        (parse-cli-args! ["--invalid-argument"])
        (catch map? m
          (is (contains? m :type))
          (is (= :cli-error (without-ns (:type m))))
          (is (= :puppetlabs.kitchensink.core/cli-error (:type m)))
          (is (contains? m :message))
          (is (re-find
                #"Unknown option.*--invalid-argument"
                (m :message)))
          (reset! got-expected-exception true)))
      (is (true? @got-expected-exception))))

  (testing "Fails if config CLI arg is not specified"
    ;; looks like `thrown-with-msg?` can't be used with slingshot. :(
    (let [got-expected-exception (atom false)]
      (try+
        (parse-cli-args! [])
        (catch map? m
          (is (contains? m :type))
          (is (= :cli-error (without-ns (:type m))))
          (is (= :puppetlabs.kitchensink.core/cli-error (:type m)))
          (is (contains? m :message))
          (is (re-find
                #"Missing required argument '--config'"
                (m :message)))
          (reset! got-expected-exception true)))
      (is (true? @got-expected-exception)))))

(deftest test-cli-args
  (testing "debug mode is off by default"
    (let [app             (bootstrap-services-with-empty-config [])
          config-service  (get-service app :ConfigService)]
      (is (false? (config/get-in-config config-service [:debug])))))

  (testing "--debug puts TK in debug mode"
    (let [app             (bootstrap-services-with-empty-config [] ["--debug"])
          config-service  (get-service app :ConfigService)]
      (is (true? (config/get-in-config config-service [:debug])))))

  (testing "TK should accept --plugins arg"
    ;; Make sure --plugins is allowed; will throw an exception if not.
    (parse-cli-args! ["--config" "yo mama"
                      "--plugins" "some/plugin/directory"])))
