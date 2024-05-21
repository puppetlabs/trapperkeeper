(ns puppetlabs.trapperkeeper.testutils.logging-test
  (:require
   [clojure.test :refer :all]
   [clojure.tools.logging :as log]
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.trapperkeeper.logging :refer [reset-logging root-logger-name]]
   [puppetlabs.trapperkeeper.testutils.logging :as tgt :refer [event->map logged?]])
  (:import
    (org.slf4j LoggerFactory)))

;; Without this, "lein test NAMESPACE" and :only invocations may fail.
(use-fixtures :once (fn [f] (reset-logging) (f)))

(deftest with-log-level-and-logging-to-atom
  (let [expected {:logger "puppetlabs.trapperkeeper.testutils.logging-test"
                  :level :info
                  :message "wlta-test"
                  :exception nil}]
    (let [log (atom [])]
      (tgt/with-log-level root-logger-name :error
        (tgt/with-logging-to-atom root-logger-name log
          (log/info "wlta-test"))
        (is (not-any? #(= expected %) (map event->map @log)))))
    (let [log (atom [])]
      (tgt/with-log-level root-logger-name :info
        (tgt/with-logging-to-atom root-logger-name log
          (log/info "wlta-test"))
        (is (some #(= expected %) (map event->map @log)))))))

(def call-with-started #'puppetlabs.trapperkeeper.testutils.logging/call-with-started)
(def find-logger #'puppetlabs.trapperkeeper.testutils.logging/find-logger)
(def log-event-listener #'puppetlabs.trapperkeeper.testutils.logging/log-event-listener)

(defn get-appenders [logger]
  (iterator-seq (.iteratorForAppenders logger)))

(deftest with-additional-log-appenders
  (let [log (atom [])
        logger (find-logger root-logger-name)
        uuid (kitchensink/uuid)
        original-appenders (get-appenders logger)
        new-appender (doto (log-event-listener
                            (fn [event] (swap! log conj event)))
                       .start)
        expected {:logger "puppetlabs.trapperkeeper.testutils.logging-test"
                  :level :error
                  :message uuid
                  :exception nil}]
    (call-with-started
     [new-appender]
     #(tgt/with-additional-log-appenders root-logger-name [new-appender]
        (is (= (set (cons new-appender original-appenders))
               (set (get-appenders logger))))
        (log/error uuid)))
    (is (= (set original-appenders)
           (set (get-appenders logger))))
    (is (some #(= expected %) (map event->map @log)))))

(deftest with-log-appenders
  (let [log (atom [])
        logger (find-logger root-logger-name)
        uuid (kitchensink/uuid)
        original-appenders (get-appenders logger)
        new-appender (doto (log-event-listener
                            (fn [event] (swap! log conj event)))
                       .start)
        expected {:logger "puppetlabs.trapperkeeper.testutils.logging-test"
                  :level :error
                  :message uuid
                  :exception nil}]
    (call-with-started
     [new-appender]
     #(tgt/with-log-appenders root-logger-name
        [new-appender]
        (is (= [new-appender] (get-appenders logger)))
        (log/error uuid)))
    (is (= (set original-appenders)
           (set (get-appenders logger))))
    (is (some #(= expected %) (map event->map @log)))))

(deftest with-log-event-listeners
  (let [log (atom [])
        uuid (kitchensink/uuid)
        expected {:logger "puppetlabs.trapperkeeper.testutils.logging-test"
                  :level :info
                  :message uuid
                  :exception nil}]
    (tgt/with-log-level root-logger-name :info
      (tgt/with-log-event-listeners root-logger-name
        [(fn [event] (swap! log conj event))]
        (log/info uuid))
      (is (some #(= expected %) (map event->map @log))))))

(deftest suppressing-log-unless-error
  (let [uuid (kitchensink/uuid)
        target (format "some random message %s" uuid)]
    (testing "log not dumped if uninteresting"
      (is (not (re-find (re-pattern target)
                        (with-out-str
                          (binding [*err* *out*]
                            (tgt/with-log-suppressed-unless-notable
                              (constantly false)
                              (log/info target))))))))
    (testing "log dumped if notable"
      (is (re-find (re-pattern target)
                   (with-out-str
                     (binding [*err* *out*]
                       (tgt/with-log-suppressed-unless-notable
                         #(= "lp0 on fire" (:message (event->map %)))
                         (log/info target)
                         (log/info "lp0 on fire")))))))))

(deftest with-test-logging
  (testing "basic matching"
    (doseq [[item test] [["foo" "foo"
                          "barbar" #"rb"
                          "baz" (fn [e]
                                  (and (= :trace (:level e))
                                       (= "baz" (:message e))))]]]
      (tgt/with-test-logging
        (log/trace item)
        (is (logged? test)))
      (tgt/with-test-logging
        (log/trace "hapax legomenon")
        (is (not (tgt/logged? test))))))
  (testing "level matches"
    (doseq [level @#'puppetlabs.trapperkeeper.testutils.logging/levels]
      (tgt/with-test-logging
        (log/log level "foo")
        (is (logged? "foo" level))))
    ;; Does not match when logged above or below correct level
    (tgt/with-test-logging
      (log/debug "debug")
      (is (not (tgt/logged? #"debug" :warn))))
    (tgt/with-test-logging
      (log/debug "debug")
      (is (not (tgt/logged? #"debug" :trace)))))
  (testing "captures parameterized slf4j messages"
    (tgt/with-test-logging
      (let [test-logger (LoggerFactory/getLogger "tk-test")]
      (.info test-logger "Log message: {}" "odelay")
      (is (tgt/logged? #"odelay"))))))

(deftest with-test-logging-debug
  (testing "basic matching"
    (doseq [[item test] [["foo" "foo"
                          "barbar" #"rb"
                          "baz" (fn [e]
                                  (and (= :trace (:level e))
                                       (= "baz" (:message e))))]]]
      (tgt/with-test-logging-debug
        (log/trace item)
        (is (logged? test)))
      (tgt/with-test-logging-debug
        (log/trace "hapax legomenon")
        (is (not (tgt/logged? test))))))
  (testing "level matches"
    (doseq [level @#'puppetlabs.trapperkeeper.testutils.logging/levels]
      (tgt/with-test-logging-debug
        (log/log level "foo")
        (is (logged? "foo" level))))
    (tgt/with-test-logging-debug
      (log/debug "debug")
      (is (not (tgt/logged? #"debug" :warn))))
    (tgt/with-test-logging
      (log/debug "debug")
      (is (not (tgt/logged? #"debug" :trace)))))
  (testing "that events are logged to *err*"
    (tgt/with-test-logging-debug
      (let [err (with-out-str (binding [*err* *out*]
                                (log/trace "foo")))]
        (is (re-matches #"\*\* Log entry: (.|\n)*" err))
        (is (re-find #":logger " err))
        (is (re-find #":level :trace" err))
        (is (re-find #":exception nil" err))
        (is (re-find #":message \"foo\"" err)))
      (is (logged? "foo")))))

(deftest with-logger-event-maps
  (let [expected {:logger "puppetlabs.trapperkeeper.testutils.logging-test"
                  :level :error
                  :message "wlgrem-test"
                  :exception nil}]
    (tgt/with-logger-event-maps root-logger-name events
      (log/error "wlgrem-test")
      (is (some #(= expected %) @events)))))

(deftest with-logged-event-maps
  (let [expected {:logger "puppetlabs.trapperkeeper.testutils.logging-test"
                  :level :error
                  :message "wlgdem-test"
                  :exception nil}]
    (tgt/with-logged-event-maps events
      (log/error "wlgdem-test")
      (is (some #(= expected %) @events)))))
