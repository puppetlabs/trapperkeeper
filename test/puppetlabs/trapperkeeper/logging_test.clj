(ns puppetlabs.trapperkeeper.logging-test
  (:import (ch.qos.logback.classic Level))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.logging :refer :all]
            [puppetlabs.trapperkeeper.logging :refer :all]
            [schema.test :as schema-test]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]))

(use-fixtures :each reset-logging-config-after-test schema-test/validate-schemas)

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
    (is (= nil (configure-logging! nil))))

  (testing "support for logback evaluator filters"
    ;; This logging config file configures some fancy logback EvaluatorFilters,
    ;; and writes the log output to a file in `target/test`.
    (configure-logging! "./dev-resources/logging/logback-evaluator-filter.xml")
    (log/info "Hi! I should get filtered.")
    (log/info "Hi! I shouldn't get filtered.")
    (log/info (IllegalStateException. "OMGOMG") "Hi! I have an exception that should get filtered.")
    (with-open [reader (io/reader "./target/test/logback-evaluator-filter-test.log")]
      (let [lines (line-seq reader)]
        (is (= 1 (count lines)))
        (is (re-matches #".*Hi! I shouldn't get filtered\..*" (first lines)))))))

(deftest test-logs-matching
  (let [log-lines '([puppetlabs.trapperkeeper.logging-test :info nil "log message1 at info"]
                    [puppetlabs.trapperkeeper.logging-test :debug nil "log message1 at debug"]
                    [puppetlabs.trapperkeeper.logging-test :warn nil "log message2 at warn"])]

    (testing "logs-matching can filter on message"
      (is (= 2 (count (logs-matching #"log message1" log-lines)))))

    (testing "logs-matching can filter on message and level"
      (is (= 1 (count (logs-matching #"log message1" log-lines :debug))))
      (is (= "log message1 at debug" (-> (logs-matching #"log message1" log-lines :debug) first :message)))
      (is (empty? (logs-matching #"log message2" log-lines :info))))))
