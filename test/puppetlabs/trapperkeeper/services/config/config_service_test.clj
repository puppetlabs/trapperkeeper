(ns puppetlabs.trapperkeeper.services.config.config-service-test
  (:import (java.io StringReader FileNotFoundException))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-cli-data]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [defservice]]))

(defprotocol ConfigTestService
  (test-fn [this ks])
  (test-fn2 [this])
  (get-in-config [this ks] [this ks default]))

(defservice test-service
            ConfigTestService
            [[:ConfigService get-in-config get-config]]
            (test-fn [this ks] (get-in-config ks))
            (test-fn2 [this] (get-config))
            (get-in-config [this ks] (get-in-config ks))
            (get-in-config [this ks default] (get-in-config ks default)))

(deftest test-config-service
  (testing "Fails if config path doesn't exist"
    (is (thrown-with-msg?
          FileNotFoundException
          #"Configuration path './foo/bar/baz' must exist and must be readable."
          (bootstrap-services-with-cli-data [test-service] {:config "./foo/bar/baz"}))))

  (testing "Can read values from a single .ini file"
    (let [app       (bootstrap-services-with-cli-data [test-service] {:config "./test-resources/config/file/config.ini"})
          test-fn   (partial test-fn (get-service app :ConfigTestService))
          test-fn-2 (partial test-fn2 (get-service app :ConfigTestService))]
      (is (= (test-fn [:foo :setting1]) "foo1"))
      (is (= (test-fn [:foo :setting2]) "foo2"))
      (is (= (test-fn [:bar :setting1]) "bar1"))

      (testing "`get-config` function"
        (is (= (test-fn-2) {:foo {:setting2 "foo2", :setting1 "foo1"}, :bar {:setting1 "bar1"} :debug false} )))))

  (testing "Can read values from a directory of .ini files"
    (let [app     (bootstrap-services-with-cli-data [test-service] {:config "./test-resources/config/dir"})
          test-fn (partial test-fn (get-service app :ConfigTestService))]
      (is (= (test-fn [:baz :setting1]) "baz1"))
      (is (= (test-fn [:baz :setting2]) "baz2"))
      (is (= (test-fn [:bam :setting1]) "bam1"))))

  (testing "A proper default value is returned if a key can't be found"
    (let [app     (bootstrap-services-with-cli-data [test-service] {:config "./test-resources/config/dir"})
          test-fn (partial get-in-config (get-service app :ConfigTestService))]
      (is (= (test-fn [:doesnt :exist] "foo") "foo")))))
