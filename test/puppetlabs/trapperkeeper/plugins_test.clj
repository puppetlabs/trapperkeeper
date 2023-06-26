(ns puppetlabs.trapperkeeper.plugins-test
  (:require [clojure.java.io :refer [file resource]]
            [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.app :refer [service-graph]]
            [puppetlabs.trapperkeeper.plugins :as plugins]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-with-empty-config]]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-jars-in-dir
  (let [jars (plugins/jars-in-dir (file "plugin-test-resources/plugins"))]
    (is (= 1 (count jars)))
    (is (= "plugin-test-resources/plugins/test-service.jar" (.getPath (first jars))))))

(deftest test-bad-directory
  (testing "TK throws an exception if --plugins is provided with a dir that does not exist."
    (is (thrown-with-msg?
         IllegalArgumentException
         #".*directory.*does not exist.*"
         (bootstrap-with-empty-config ["--plugins" "/this/does/not/exist"])))))

(deftest test-no-duplicates
  (testing "duplicate test passes on .jar with just a service in it"
    ;; `verify-no-duplicate-resources` throws an exception if a duplicate is found.
    (plugins/verify-no-duplicate-resources (file "plugin-test-resources/plugins/test-service.jar"))))

(deftest test-duplicates
  (testing "duplicate test fails when an older version of kitchensink is included"
    (is (thrown-with-msg?
         IllegalArgumentException
         #".*Class or namespace.*found in both.*"
         (plugins/verify-no-duplicate-resources
          (file "plugin-test-resources/bad-plugins"))))))

(deftest test-plugin-service
  (testing "TK can load and use service defined in plugin .jar"
    (let [app (bootstrap-with-empty-config
               ["--plugins" "./plugin-test-resources/plugins"
                "--bootstrap-config" "./dev-resources/bootstrapping/plugin/bootstrap.cfg"])
          service-fn (-> (service-graph app)
                         :PluginTestService
                         :moo)]
      (is (= "This message comes from the plugin test service." (service-fn)))
      ;; Can it also load resources from that jar
      (is (resource "test_services/plugin_test_services.clj")))))
