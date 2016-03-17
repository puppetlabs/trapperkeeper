(ns puppetlabs.trapperkeeper.core-test
  (:require [clojure.test :refer :all]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :refer [without-ns]]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.internal :refer [parse-cli-args!]]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as testutils]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [schema.test :as schema-test]))

(use-fixtures :each schema-test/validate-schemas logging/reset-logging-config-after-test)

(defprotocol FooService
  (foo [this]))

(deftest dependency-error-handling
  (testing "missing service dependency throws meaningful message and logs error"
    (let [broken-service (service
                          [[:MissingService f]]
                          (init [this context] (f) context))]
      (logging/with-test-logging
        (is (thrown-with-msg?
             RuntimeException #"Service ':MissingService' not found"
             (testutils/bootstrap-services-with-empty-config [broken-service])))
        (is (logged? #"Error during app buildup!" :error)
            "App buildup error message not logged"))))

  (testing "missing service function throws meaningful message and logs error"
    (let [test-service    (service FooService
                                   []
                                   (foo [this] "foo"))
          broken-service  (service
                           [[:FooService bar]]
                           (init [this context] (bar) context))]
      (logging/with-test-logging
        (is (thrown-with-msg?
             RuntimeException
             #"Service function 'bar' not found in service 'FooService"
             (testutils/bootstrap-services-with-empty-config
              [test-service
               broken-service])))
        (is (logged? #"Error during app buildup!" :error)
            "App buildup error message not logged")))
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

  (testing "TK should allow the user to omit the --config arg"
    ;; Make sure args will be parsed if no --config arg is provided; will throw an exception if not
    (parse-cli-args! [])
    (is (true? true)))

  (testing "TK should use an empty config if none is specified"
    ;; Make sure data will be parsed if no path is provided; will throw an exception if not.
    (config/parse-config-data {})
    (is (true? true))))

(deftest test-cli-args
  (testing "debug mode is off by default"
    (testutils/with-app-with-empty-config app []
      (let [config-service (get-service app :ConfigService)]
        (is (false? (config/get-in-config config-service [:debug]))))))

  (testing "--debug puts TK in debug mode"
    (testutils/with-app-with-cli-args app [] ["--config" testutils/empty-config "--debug"]
      (let [config-service (get-service app :ConfigService)]
        (is (true? (config/get-in-config config-service [:debug]))))))

  (testing "TK should accept --plugins arg"
    ;; Make sure --plugins is allowed; will throw an exception if not.
    (parse-cli-args! ["--config" "yo mama"
                      "--plugins" "some/plugin/directory"])))
