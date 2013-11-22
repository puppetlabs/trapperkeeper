(ns puppetlabs.trapperkeeper.main-test
  (:import (java.io StringReader))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.main :refer :all]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [parse-and-bootstrap]]
            [slingshot.slingshot :refer [try+]]))

(testing "CLI arg parsing"
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
    (is (thrown-with-msg?
          Exception
          #"not a valid argument"
          (parse-cli-args! ["--invalid-argument"]))))

  (testing "The CLI service has the CLI data."
    (let [bootstrap-config "

puppetlabs.trapperkeeper.examples.bootstrapping.test-services/cli-test-service

"
          config-file-path  "./test-resources/config/empty.ini"
          cli-data          (parse-cli-args! ["--config" config-file-path "--debug"])
          app-with-cli-args (parse-and-bootstrap (StringReader. bootstrap-config) cli-data)
          cli-data-fn       (trapperkeeper/get-service-fn app-with-cli-args :cli-service :cli-data)]
      (is (not (empty? (cli-data-fn))))
      (is (cli-data-fn :debug))
      (is (= config-file-path (cli-data-fn :config)))))

  (testing "Fails if config CLI arg is not specified"
    (try+
      (main)
      (catch map? m
        (is (contains? m :error-message))
        (is (re-matches
              #"(?s)^.*Missing required argument '--config'.*$"
              (m :error-message)))))))
