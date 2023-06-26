(ns puppetlabs.trapperkeeper.logging-test
  (:require [clojure.java.io :as io]
            clojure.stacktrace
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.logging :as tk-logging]
            [puppetlabs.trapperkeeper.testutils.logging :refer :all]
            [schema.test :as schema-test])
  (:import (ch.qos.logback.classic Level)))

(use-fixtures :each reset-logging-config-after-test schema-test/validate-schemas)

(deftest test-catch-all-logger
  (testing "catch-all-logger ensures that message from an exception is logged"
    (with-test-logging
      ;; Prevent the stacktrace from being printed out
      (with-redefs [clojure.stacktrace/print-cause-trace (fn [_e] nil)]
        (tk-logging/catch-all-logger
         (Exception. "This exception is expected; testing error logging")
         "this is my error message"))
      (is (logged? #"this is my error message" :error)))))


(deftest with-test-logging-on-separate-thread
  (testing "test-logging captures log messages from `future` threads"
    (with-test-logging
      (let [log-future (future
                         (log/error "yo yo yo"))]
        @log-future
        (is (logged? #"yo yo yo" :error)))))
  (testing "threading doesn't break stuff"
    (with-test-logging
      (let [done? (promise)]
        (.start (Thread. (fn []
                           (log/info "test thread")
                           (deliver done? true))))
        (is (true? @done?))
        (is (logged? #"test thread" :info))))))

(deftest test-logging-configuration
  (testing "Calling `configure-logging!` with a logback.xml file"
    (tk-logging/configure-logging! "./dev-resources/logging/logback-debug.xml")
    (is (= (Level/DEBUG) (.getLevel (tk-logging/root-logger)))))

  (testing "Calling `configure-logging!` with another logback.xml file
            in case the default logging level is DEBUG"
    (tk-logging/configure-logging! "./dev-resources/logging/logback-warn.xml")
    (is (= (Level/WARN) (.getLevel (tk-logging/root-logger)))))

  (testing "a logging config file isn't required"
    ;; This looks strange, but we're trying to make sure that there are
    ;; no exceptions thrown when we configure logging without a log config file.
    (is (= nil (tk-logging/configure-logging! nil))))

  (testing "support for logback evaluator filters"
    ;; This logging config file configures some fancy logback EvaluatorFilters,
    ;; and writes the log output to a file in `target/test`.
    (tk-logging/configure-logging! "./dev-resources/logging/logback-evaluator-filter.xml")
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
      ;; ignore deprecations
      #_:clj-kondo/ignore
      (is (= 2 (count (logs-matching #"log message1" log-lines)))))

    (testing "logs-matching can filter on message and level"
      ;; ignore deprecations
      #_:clj-kondo/ignore
      (is (= 1 (count (logs-matching #"log message1" log-lines :debug))))
      #_:clj-kondo/ignore
      (is (= "log message1 at debug" (-> (logs-matching #"log message1" log-lines :debug) first :message)))
      #_:clj-kondo/ignore
      (is (empty? (logs-matching #"log message2" log-lines :info))))))
