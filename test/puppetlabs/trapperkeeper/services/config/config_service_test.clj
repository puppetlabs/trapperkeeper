(ns puppetlabs.trapperkeeper.services.config.config-service-test
  (:import (java.io StringReader FileNotFoundException))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services.config.config-service :refer [config-service]]
            [puppetlabs.trapperkeeper.core :refer [defservice parse-cli-args! bootstrap* get-service-fn]]))

(defservice test-service
   {:depends [[:config-service get-in-config]]
    :provides []}
   {:test-fn (fn [ks] (get-in-config ks))})

(defn bootstrap-with-cli-args
  "Helper function to parse CLI args, call bootstrap, and return our
  test function."
  [args]
  (let [cli-data  (parse-cli-args! args)
        app       (bootstrap* [(test-service) (config-service)] cli-data)]
    (get-service-fn app :test-service :test-fn)))

(deftest test-config-service
  (testing "Fails if config CLI arg is not specified"
    (is (thrown-with-msg?
          IllegalStateException
          #"Command line argument --config \(or -c\) is required by the config service"
          (bootstrap-with-cli-args []))))

  (testing "Fails if config path doesn't exist"
    (is (thrown-with-msg?
          FileNotFoundException
          #"Configuration path './foo/bar/baz' must exist and must be readable."
          (bootstrap-with-cli-args ["--config" "./foo/bar/baz"]))))

  (testing "Can read values from a single .ini file"
    (let [test-fn (bootstrap-with-cli-args
                    ["--config" "./test/puppetlabs/trapperkeeper/examples/config/file/config.ini"])]
      (is (= (test-fn [:foo :setting1]) "foo1"))
      (is (= (test-fn [:foo :setting2]) "foo2"))
      (is (= (test-fn [:bar :setting1]) "bar1"))))

  (testing "Can read values from a directory of .ini files"
    (let [test-fn (bootstrap-with-cli-args
                    ["--config" "./test/puppetlabs/trapperkeeper/examples/config/dir"])]
      (is (= (test-fn [:baz :setting1]) "baz1"))
      (is (= (test-fn [:baz :setting2]) "baz2"))
      (is (= (test-fn [:bam :setting1]) "bam1")))))


