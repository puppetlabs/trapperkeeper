(ns puppetlabs.trapperkeeper.services.config.config-service-test
  (:import (java.io StringReader FileNotFoundException))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :refer [bootstrap*]]
            [puppetlabs.trapperkeeper.services :refer [defservice get-service-fn]]))

(defservice test-service
   {:depends [[:config-service get-in-config get-config]]
    :provides []}
   {:test-fn (fn [ks] (get-in-config ks))
    :test-fn-2 (fn [] (get-config))})

(deftest test-config-service
  (testing "Fails if config path doesn't exist"
    (is (thrown-with-msg?
          FileNotFoundException
          #"Configuration path './foo/bar/baz' must exist and must be readable."
          (bootstrap* [(test-service)] {:config "./foo/bar/baz"}))))

  (testing "Can read values from a single .ini file"
    (let [app       (bootstrap* [(test-service)] {:config "./test-resources/config/file/config.ini"})
          test-fn   (get-service-fn app :test-service :test-fn)
          test-fn-2 (get-service-fn app :test-service :test-fn-2)]
      (is (= (test-fn [:foo :setting1]) "foo1"))
      (is (= (test-fn [:foo :setting2]) "foo2"))
      (is (= (test-fn [:bar :setting1]) "bar1"))

      (testing "`get-config` function"
        (is (= (test-fn-2) {:foo {:setting2 "foo2", :setting1 "foo1"}, :bar {:setting1 "bar1"} :debug false} )))))

  (testing "Can read values from a directory of .ini files"
    (let [app     (bootstrap* [(test-service)] {:config "./test-resources/config/dir"})
          test-fn (get-service-fn app :test-service :test-fn)]
      (is (= (test-fn [:baz :setting1]) "baz1"))
      (is (= (test-fn [:baz :setting2]) "baz2"))
      (is (= (test-fn [:bam :setting1]) "bam1")))))
