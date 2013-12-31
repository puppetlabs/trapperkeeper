(ns puppetlabs.trapperkeeper.core-test
  (:require [clojure.test :refer :all]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :refer [without-ns]]
            [puppetlabs.trapperkeeper.services :refer [service ]]
            [puppetlabs.trapperkeeper.app :refer [get-service-fn]]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer :all]))

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
          cli-data         (trapperkeeper/parse-cli-args!
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
          (trapperkeeper/parse-cli-args! ["--invalid-argument"]))))

  (testing "Fails if config CLI arg is not specified"
    ;; looks like `thrown-with-msg?` can't be used with slingshot. :(
    (let [got-expected-exception (atom false)]
      (try+
        (trapperkeeper/parse-cli-args! [])
        (catch map? m
          (is (contains? m :type))
          (is (= :cli-error (without-ns (:type m))))
          (is (= :puppetlabs.kitchensink.core/cli-error (:type m)))
          (is (contains? m :message))
          (is (re-matches
                #"(?s)^.*Missing required argument '--config'.*$"
                (m :message)))
          (reset! got-expected-exception true)))
      (is (true? @got-expected-exception)))))

(deftest test-cli-args
  (testing "debug mode is off by default"
    (let [app           (bootstrap-services-with-empty-config [])
          get-in-config (get-service-fn app :config-service :get-in-config)]
      (is (false? (get-in-config [:debug])))))

  (testing "--debug puts TK in debug mode"
    (let [app           (bootstrap-services-with-empty-config [] ["--debug"])
          get-in-config (get-service-fn app :config-service :get-in-config)]
      (is (true? (get-in-config [:debug])))))

  (testing "TK should accept --plugins arg"
    ;; Make sure --plugins is allowed; will throw an exception if not.
    (trapperkeeper/parse-cli-args! ["--config" "yo mama"
                                    "--plugins" "some/plugin/directory"])))
