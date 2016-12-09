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
            [schema.test :as schema-test]
            [puppetlabs.kitchensink.core :as ks]))

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
          restart-file   "/fake/restart/file"
          cli-data         (parse-cli-args!
                            ["--debug"
                             "--bootstrap-config" bootstrap-file
                             "--config" config-dir
                             "--restart-file" restart-file])]
      (is (= bootstrap-file (cli-data :bootstrap-config)))
      (is (= config-dir (cli-data :config)))
      (is (= restart-file (cli-data :restart-file)))
      (is (cli-data :debug))))

  (testing "Invalid CLI data"
    (let [got-expected-exception (atom false)]
      (try+
        (parse-cli-args! ["--invalid-argument"])
        (catch map? m
          (is (contains? m :kind))
          (is (= :cli-error (without-ns (:kind m))))
          (is (= :puppetlabs.kitchensink.core/cli-error (:kind m)))
          (is (contains? m :msg))
          (is (re-find
               #"Unknown option.*--invalid-argument"
               (m :msg)))
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

(deftest restart-file-config
  (let [tk-config-file-with-restart (ks/temp-file "restart-global" ".conf")
        tk-restart-file "/my/tk-restart-file"
        cli-restart-file "/my/cli-restart-file"]
    (spit tk-config-file-with-restart
          (format "global: {\nrestart-file: %s\n}" tk-restart-file))
    (testing "restart-file setting comes from TK config when CLI arg absent"
      (let [config (config/parse-config-data
                    {:config (str tk-config-file-with-restart)})]
        (is (= tk-restart-file (get-in config [:global :restart-file])))))
    (testing "restart-file setting comes from CLI arg when no TK config setting"
      (let [empty-tk-config-file (ks/temp-file "empty" ".conf")
            config (config/parse-config-data
                    {:config (str empty-tk-config-file)
                     :restart-file cli-restart-file})]
        (is (= cli-restart-file (get-in config [:global :restart-file])))))
    (testing "restart-file setting comes from CLI arg even when set in TK config"
      (let [config (config/parse-config-data
                    {:config (str tk-config-file-with-restart)
                     :restart-file cli-restart-file})]
        (is (= cli-restart-file (get-in config [:global :restart-file])))))))
