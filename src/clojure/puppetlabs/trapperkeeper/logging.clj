(ns puppetlabs.trapperkeeper.logging
  (:import [org.apache.log4j
            PropertyConfigurator ConsoleAppender PatternLayout Logger Level])
  (:require [clojure.stacktrace :refer [print-cause-trace]]
            [clojure.tools.logging :as log]))

(defn catch-all-logger
  "A logging function useful for catch-all purposes, that is, to
  ensure that a log message gets in front of a user the best we can
  even if that means duplicated output.

  This is really only suitable for _last-ditch_ exception handling,
  where we want to make sure an exception is logged (because nobody
  higher up in the stack will log it for us)."
  ([exception]
   (catch-all-logger exception "Uncaught exception"))
  ([exception message]
   (print-cause-trace exception)
   (flush)
   (log/error exception message)))

(defn create-console-appender
  "Instantiates and returns a logging appender configured to write to
  the console, using the standard logging configuration.

  `level` is an optional argument (of type `org.apache.log4j.Level`)
  indicating the logging threshold for the new appender.  Defaults
  to `DEBUG`."
  ([]
   (create-console-appender Level/DEBUG))
  ([level]
   {:pre [(instance? Level level)]}
   (let [layout (PatternLayout. "%d %-5p [%t] [%c{2}] %m%n")]
     (doto (ConsoleAppender.)
       (.setLayout layout)
       (.setThreshold level)
       (.activateOptions)))))

(defn add-console-logger!
  "Adds a console logger to the current logging configuration, and ensures
  that the root logger is set to log at the logging level of the new
  logger or finer.

  `level` is an optional argument (of type `org.apache.log4j.Level`)
  indicating the logging threshold for the new logger.  Defaults
  to `DEBUG`."
  ([]
   (add-console-logger! Level/DEBUG))
  ([level]
   {:pre [(instance? Level level)]}
   (let [root-logger (Logger/getRootLogger)]
     (.addAppender root-logger (create-console-appender level))
     (if (> (.toInt (.getLevel root-logger))
            (.toInt level))
       (.setLevel root-logger level)))))

(defn configure-logger-via-file!
  "Reconfigures the current logger based on the supplied configuration
  file. You can optionally supply a delay (in millis) that governs how
  often we'll check the config file for updates, and thus reconfigure
  the logger live."
  ([logging-conf-file]
   {:pre [(string? logging-conf-file)]}
   (configure-logger-via-file! logging-conf-file 10000))
  ([logging-conf-file reload-interval]
   {:pre [(string? logging-conf-file)
          (number? reload-interval)
          (pos? reload-interval)]}
   (PropertyConfigurator/configureAndWatch logging-conf-file reload-interval)))

(defn configure-logging!
  "Takes a file path which can define how to configure the logging system.
  Also takes an optional `debug` flag which turns on debug logging."
  ([logging-conf-file]
   (configure-logging! logging-conf-file false))
  ([logging-conf-file debug]
   (when logging-conf-file
     (configure-logger-via-file! logging-conf-file))
   (when debug
     (add-console-logger! Level/DEBUG)
     (log/debug "Debug logging enabled"))))
