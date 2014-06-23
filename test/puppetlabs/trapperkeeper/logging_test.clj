(ns puppetlabs.trapperkeeper.logging-test
  (:import (ch.qos.logback.classic Level))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.logging :refer :all]
            [puppetlabs.trapperkeeper.logging :refer :all]))

(use-fixtures :each reset-logging-config-after-test)

(deftest test-catch-all-logger
  (testing "catch-all-logger ensures that message from an exception is logged"
    (with-test-logging
      ;; Prevent the stacktrace from being printed out
      (with-redefs [clojure.stacktrace/print-cause-trace (fn [e] nil)]
        (catch-all-logger
          (Exception. "This exception is expected; testing error logging")
          "this is my error message"))
      (is (logged? #"this is my error message" :error)))))

(deftest test-logging-configuration
  (testing "Calling `configure-logging!` with a logback.xml file"
    (configure-logging! "./dev-resources/logging/logback-debug.xml")
    (is (= (Level/DEBUG) (.getLevel (root-logger)))))

  (testing "Calling `configure-logging!` with another logback.xml file
            in case the default logging level is DEBUG"
    (configure-logging! "./dev-resources/logging/logback-warn.xml")
    (is (= (Level/WARN) (.getLevel (root-logger)))))

  (testing "a logging config file isn't required"
    ;; This looks strange, but we're trying to make sure that there are
    ;; no exceptions thrown when we configure logging without a log config file.
    (is (= nil (configure-logging! nil)))))
