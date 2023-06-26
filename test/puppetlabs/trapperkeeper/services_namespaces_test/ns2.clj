(ns puppetlabs.trapperkeeper.services-namespaces-test.ns2
  (:require
    [clojure.test :refer :all]
    [puppetlabs.kitchensink.testutils.fixtures :refer [with-no-jvm-shutdown-hooks]]
    [puppetlabs.trapperkeeper.core :as trapperkeeper]
    [puppetlabs.trapperkeeper.services-namespaces-test.ns1 :as ns1]
    [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-empty-config]]
    [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas with-no-jvm-shutdown-hooks)

(trapperkeeper/defservice foo-service
  ns1/FooService
  []
  (foo [this] "foo"))

(deftest test-service-namespaces
  (testing "can boot service defined in different namespace than protocol"
    (bootstrap-services-with-empty-config [foo-service])
    (is (true? true))))
