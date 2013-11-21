(ns puppetlabs.trapperkeeper.services.config.config-service-test
  (:import (java.io StringReader FileNotFoundException))
  (:require [clojure.test :refer :all]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.trapperkeeper.core :refer [defservice parse-cli-args! bootstrap* get-service-fn]]))

(defservice test-service
   {:depends [[:config-service get-in-config get-config]]
    :provides []}
   {:test-fn (fn [ks] (get-in-config ks))
    :test-fn-2 (fn [] (get-config))})

(defn bootstrap-with-cli-args
  "Helper function to parse CLI args, call bootstrap, and return the
  bootstrapped TrapperKeeperApp."
  [args]
  (let [cli-data  (parse-cli-args! args)]
    (bootstrap* [(test-service)] cli-data)))

(deftest test-config-service
  (testing "Fails if config CLI arg is not specified"
    (try+
      (bootstrap-with-cli-args [])
      (catch map? m
        (is (contains? m :error-message))
        (is (re-matches #"(?s)^.*Missing required argument '--config'.*$" (m :error-message))))))

  (testing "Fails if config path doesn't exist"
    (is (thrown-with-msg?
          FileNotFoundException
          #"Configuration path './foo/bar/baz' must exist and must be readable."
          (bootstrap-with-cli-args ["--config" "./foo/bar/baz"]))))

  (testing "Can read values from a single .ini file"
    (let [app (bootstrap-with-cli-args
                    ["--config" "./test/puppetlabs/trapperkeeper/examples/config/file/config.ini"])
          test-fn (get-service-fn app :test-service :test-fn)
          test-fn-2 (get-service-fn app :test-service :test-fn-2)]
      (is (= (test-fn [:foo :setting1]) "foo1"))
      (is (= (test-fn [:foo :setting2]) "foo2"))
      (is (= (test-fn [:bar :setting1]) "bar1"))

      (testing "`get-config` function"
        (is (= (test-fn-2) {:foo {:setting2 "foo2", :setting1 "foo1"}, :bar {:setting1 "bar1"}} )))))

  (testing "Can read values from a directory of .ini files"
    (let [app (bootstrap-with-cli-args
                    ["--config" "./test/puppetlabs/trapperkeeper/examples/config/dir"])
          test-fn (get-service-fn app :test-service :test-fn)]
      (is (= (test-fn [:baz :setting1]) "baz1"))
      (is (= (test-fn [:baz :setting2]) "baz2"))
      (is (= (test-fn [:bam :setting1]) "bam1")))))
