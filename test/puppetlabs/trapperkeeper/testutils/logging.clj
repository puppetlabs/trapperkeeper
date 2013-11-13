(ns puppetlabs.trapperkeeper.testutils.logging
  (:import [org.apache.log4j Logger AppenderSkeleton Level]
           (java.util.regex Pattern))
  (:require [clojure.tools.logging.impl :as impl]
            [clojure.tools.logging :refer [*logger-factory*]]
            [clojure.test :refer [assert-expr]]))

(def ^{:doc "A dynamic var that is bound to an atom containing all of the log entries
             that have occurred during a test, when using `with-test-logging`."
       :dynamic true}
  *test-logs*
  nil)

(defn- log-entry->map
  [log-entry]
  {:namespace (get log-entry 0)
   :level     (get log-entry 1)
   :exception (get log-entry 2)
   :message   (get log-entry 3)})

(defn logs-matching
  "Given a regular expression pattern and a sequence of log messages (in the format
  used by `clojure.tools.logging`, return only the logs whose message matches the
  specified regular expression pattern.  (Intended to be used alongside
  `with-log-output` for tests that are validating log output.)  The result is
  a sequence of maps, each of which contains the following keys:
  `:namespace`, `:level`, `:exception`, and `:message`."
  [pattern logs]
  {:pre  [(instance? java.util.regex.Pattern pattern)
          (coll? logs)]}
  ;; the logs are formatted as sequences, where the string at index 3 contains
  ;; the actual log message.
  (let [matches (filter #(re-find pattern (get % 3)) logs)]
    (map log-entry->map matches)))

(defn log-to-console
  "Utility function called by atom-logger and atom-appender to log entries to the
  console when running in debug mode."
  [entry]
  (println "** Log entry:" entry))

(defn atom-logger
  "A logger factory that logs output to the supplied atom"
  ([output-atom] (atom-logger false))
  ([output-atom debug]
   (reify impl/LoggerFactory
     (name [_] "test factory")
     (get-logger [_ log-ns]
       (reify impl/Logger
         (enabled? [_ level] true)
         (write! [_ lvl ex msg]
           (let [entry [(str log-ns) lvl ex msg]]
             (when debug (log-to-console entry))
             (swap! output-atom conj entry))))))))

(defn atom-appender
  "Creates a log4j appender that writes log messages to the supplied atom"
  ([output-atom] (atom-appender false))
  ([output-atom debug]
   (proxy [AppenderSkeleton] []
     (append [logging-event]
       (let [throwable-info  (.getThrowableInformation logging-event)
             ex              (if throwable-info (.getThrowable throwable-info))
             entry           [(.getLoggerName logging-event)
                              (.getLevel logging-event)
                              ex
                              (str (.getMessage logging-event))]]
         (when debug (log-to-console entry))
         (swap! output-atom conj entry)))
     (close []))))

(defmacro with-log-output-atom
  "This is a utility macro, intended for use by other macros such as
  `with-test-logging`.

  Given an atom whose value is a sequence, sets up a temporary logger to capture
  all log output to the sequence, and evaluates `body` in this logging context.

  `log-output-atom` - Inside of `body`, this atom will be used to store
  the sequence of log messages that have been logged so far.  You can access the
  individual log messages by dereferencing the atom."
  [log-output-atom options & body]
  `(let [root-logger#     (Logger/getRootLogger)
         orig-appenders#  (vec (enumeration-seq (.getAllAppenders root-logger#)))
         orig-levels#     (into {} (map #(vector % (.getThreshold %)) orig-appenders#))
         temp-appender#   (atom-appender ~log-output-atom (~options :debug))]
     (.setName temp-appender# "testutils-temp-log-appender")
     (try
       (doseq [orig-appender# orig-appenders#]
         (.setThreshold orig-appender# Level/OFF))
       (.addAppender root-logger# temp-appender#)
       (binding [clojure.tools.logging/*logger-factory*
                 (atom-logger
                   ~log-output-atom
                   (~options :debug))]
         ~@body)
       (finally
         (.removeAppender root-logger# temp-appender#)
         (doseq [orig-appender# orig-appenders#]
           (.setThreshold
             orig-appender#
             (orig-levels# orig-appender#)))))))

(defmacro with-log-output
  "Sets up a temporary logger to capture all log output to a sequence, and
  evaluates `body` in this logging context.

  `log-output-var` - Inside of `body`, the variable named `log-output-var`
  is a clojure atom containing the sequence of log messages that have been logged
  so far.  You can access the individual log messages by dereferencing this
  variable (with either `deref` or `@`).

  Example:

      (with-log-output logs
        (log/info \"Hello There\")
        (is (= 1 (count (logs-matching #\"Hello There\" @logs)))))"
  [log-output-var & body]
  `(let [~log-output-var  (atom [])]
     (with-log-output-atom ~log-output-var {:debug false} ~@body)))

(defmacro with-test-logging
  "Executes `body` in a context in which all log messages are captured and
  available to write test assertions against, using the `logged?` assertion
  expression.  Example:

      (with-test-logging
        (log/info \"hi\")
        (is (logged? #\"^hi$\"))
        (is (logged? #\"^hi$\" :info)))"
  [& body]
  `(let [test-logs# (atom [])]
     (binding [puppetlabs.trapperkeeper.testutils.logging/*test-logs* test-logs#]
       (with-log-output-atom test-logs# {:debug false} ~@body))))

(defmacro with-test-logging-debug
  "This macro is the same as `with-test-logging`, except that it will also cause
  all log entries to be printed to the console during the test run.  It is
  only intended for debugging while developing tests that use `with-test-logging`."
  [& body]
  `(let [test-logs# (atom [])]
     (binding [puppetlabs.trapperkeeper.testutils.logging/*test-logs* test-logs#]
       (with-log-output-atom test-logs# {:debug true} ~@body))))

(defmethod clojure.test/assert-expr 'logged? [msg form]
  "This is an assertion expression for use with `clojure.test/is`.  It
  must be used inside a call to `with-test-logging`.  Asserts
  that exactly one log message occurred during the test which matches
  the specified pattern and log level.

  Legal log levels correspond to those of `clojure.tools.logging`:

      :trace :debug :info :warn :error :fatal

  Example:

      (with-test-logging
        (log/info \"hi\")
        (is (logged? #\"^hi$\"))
        (is (logged? #\"^hi$\" :info)))"
  (let [pattern       (nth form 1)
        level         (nth form 2 nil)
        legal-levels  #{nil :trace :debug :info :warn :error :fatal}
        debug-msg     "; `with-test-logging-debug` may be useful for debugging tests."]
    (when-not (instance? Pattern pattern)
      (throw (IllegalArgumentException.
               "First argument to `logged?` must be a java.util.regex.Pattern")))
    (when-not (contains? legal-levels level)
      (throw (IllegalArgumentException.
               (str "Optional second argument to `logged?` must be one of "
                    legal-levels "; illegal value: '" level "'"))))
    `(let [logs#    @puppetlabs.trapperkeeper.testutils.logging/*test-logs*
           matches# (logs-matching ~pattern logs#)]
       (cond
         (not (= 1 (count matches#)))
         (clojure.test/do-report
           {:type      :fail
            :message   ~msg
            :expected  (str "Exactly one log message matching the pattern '"
                            ~pattern "'")
            :actual    (str (count matches#) " matches" ~debug-msg)})

         (and ~level (not (= ~level (-> matches# first :level))))
         (clojure.test/do-report
           {:type      :fail
            :message   ~msg
            :expected  (str "Expected level " ~level " for log message matching pattern '"
                            ~pattern "'")
            :actual    (str "Actual level: " (-> matches# first :level) ~debug-msg)})

         :else
         (clojure.test/do-report
           {:type      :pass
            :message   ~msg
            :expected  '~form
            :actual    '~form})))))