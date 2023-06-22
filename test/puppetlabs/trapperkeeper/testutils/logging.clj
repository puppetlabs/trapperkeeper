(ns puppetlabs.trapperkeeper.testutils.logging
  (:require
    [clojure.set :as set]
    [clojure.test]
    [clojure.tools.logging.impl :as impl]
    [me.raynes.fs :as fs]
    [puppetlabs.kitchensink.core :as kitchensink]
    [puppetlabs.trapperkeeper.logging :as pl-log
     :refer [root-logger-name]]
    [schema.core :as s])
  (:import
    (ch.qos.logback.classic Level)
    (ch.qos.logback.classic.encoder PatternLayoutEncoder)
    (ch.qos.logback.core Appender FileAppender)
    (ch.qos.logback.core AppenderBase)
    (ch.qos.logback.core.spi LifeCycle)
    (java.util.regex Pattern)
    (org.slf4j LoggerFactory)))

;; Note that the logging configuration is a global resource, so
;; simultaneous calls to reset-logging, configure-logging!, etc. may
;; interfere with many of these calls.

(def ^:private keyword-levels
  {:trace Level/TRACE
   :debug Level/DEBUG
   :info Level/INFO
   :warn Level/WARN
   :error Level/ERROR})

(def ^:private level-keywords (set/map-invert keyword-levels))

(def ^:private levels (set (keys keyword-levels)))

(defn event->map
  "Returns {:logger name :level lvl :exception throwable :message msg}
  for the given event.  Note that this does not convert any nil
  messages to \"\"."
  [event]
  {:logger (.getLoggerName event)
   :level  (level-keywords (.getLevel event))
   :message (.getFormattedMessage event)
   :exception (.getThrowableProxy event)})

;; Perhaps make call-with-started and with-started public in
;; kitchensink or some other namespace?

(defn- call-with-started [objs f]
  (if-not (seq objs)
    (f)
    (let [[obj & remainder] objs]
      (try
        (call-with-started remainder f)
        (finally (.stop obj))))))

(defmacro ^:private with-started
  "Ensures that if a given name's init form executes without throwing
  an exception, (.stop name) will be called before returning from
  with-started.  It is the responsibility of the init form to make
  sure the object has been started.  This macro behaves like
  with-open, but with respect to .stop instead of .close."
  [bindings & body]
  (let [names (take-nth 2 bindings)
        initializers (take-nth 2 (rest bindings))]
    `(let [started-objs# [~@initializers]]
       (#'puppetlabs.trapperkeeper.testutils.logging/call-with-started
        started-objs#
        (fn []
          (apply (fn [~@names] ~@body) started-objs#))))))

(defn- find-logger [id]
  (LoggerFactory/getLogger (if (class? id) id (str id))))

(defn call-with-log-level
  "Sets the (logback) log level for the logger specified by logger-id
  during the evaluation of f.  If logger-id is not a class, it will be
  converted via str, and the level must be a clojure.tools.logging
  key, i.e. :info, :error, etc."
  [logger-id level f]
  ;; Assumes use of logback (i.e. logger supports Levels).
  (let [logger (find-logger logger-id)
        original-level (.getLevel logger)]
    (try
      (.setLevel logger (level keyword-levels))
      (f)
      (finally (.setLevel logger original-level)))))

(defmacro with-log-level
  "Sets the (logback) log level for the logger specified by logger-id
  during the evaluation of body.  If logger-id is not a class, it will
  be converted via str, and the level must be a clojure.tools.logging
  key, i.e. :info, :error, etc."
  [logger-id level & body]
  `(call-with-log-level ~logger-id ~level (fn [] ~@body)))

(defn- log-event-listener
  "Returns a log Appender that will call (listen event) for each log event."
  [listen]
  ;; No clue yet if we're supposed to start with a default name.
  (let [name (atom (str "tk-log-listener-" (kitchensink/uuid)))
        started? (atom false)]
    (reify
      Appender
      (doAppend [_this event] (when @started? (listen event)))
      (getName [_this] @name)
      (setName [_this x] (reset! name x))
      LifeCycle
      (start [_this] (reset! started? true))
      (stop [_this] (reset! started? false))
      (isStarted [_this] @started?))))

(defn call-with-additional-log-appenders
  "Adds the specified appenders to the logger specified by logger-id,
  calls f, and then removes them.  If logger-id is not a class, it
  will be converted via str."
  [logger-id appenders f]
  (let [logger (find-logger logger-id)]
    (try
      (doseq [appender appenders]
        (.addAppender logger appender))
      (f)
      (finally
        (doseq [appender appenders]
          (.detachAppender logger appender))))))

(defmacro with-additional-log-appenders
  "Adds the specified appenders to the logger specified by logger-id,
  evaluates body, and then removes them.  If logger-id is not a class,
  it will be converted via str."
  [logger-id appenders & body]
  `(call-with-additional-log-appenders ~logger-id ~appenders (fn [] ~@body)))

(defn call-with-log-appenders
  "Replaces the appenders of the logger specified by logger-id with
  the specified appenders, calls f, and then restores the original
  appenders.  If logger-id is not a class, it will be converted via
  str."
  [logger-id appenders f]
  (let [logger (find-logger logger-id)
        original-appenders (iterator-seq (.iteratorForAppenders logger))]
    (try
      (doseq [appender original-appenders]
        (.detachAppender logger appender))
      (call-with-additional-log-appenders logger-id appenders f)
      (finally
        (doseq [appender original-appenders]
          (.addAppender logger appender))))))

(defmacro with-log-appenders
  "Replaces the appenders of the logger specified by logger-id with
  the specified appenders, evaluates body, and then restores the
  original appenders.  If logger-id is not a class, it will be
  converted via str."
  [logger-id appenders & body]
  `(call-with-log-appenders ~logger-id ~appenders (fn [] ~@body)))

(defn call-with-additional-log-event-listeners
  "For each listen in listens, calls (listen event) for each logger-id
  event produced during the evaluation of f.  If logger-id is not a
  class, it will be converted via str."
  [logger-id listens f]
  (letfn [(set-up [listens listeners]
            (if-not (seq listens)
              (call-with-additional-log-appenders logger-id listeners f)
              (let [[listen & remainder] listens]
                (with-started [listener (doto (log-event-listener listen)
                                          .start)]
                  (set-up remainder (conj listeners listener))))))]
    (set-up listens [])))

(defmacro with-additional-log-event-listeners
  "For each listen in listens, calls (listen event) for each logger-id
  event produced during the evaluation of body.  If logger-id is not a
  class, it will be converted via str."
  [logger-id listens & body]
  `(call-with-additional-log-event-listeners ~logger-id ~listens
                                             (fn [] ~@body)))

(defn call-with-log-event-listeners
  "For each listen in listens, calls (listen event) for each logger-id
  event produced during the evaluation of f, after removing any
  existing log appenders.  If logger-id is not a class, it will be
  converted via str."
  [logger-id listens f]
  (letfn [(set-up [listens listeners]
            (if-not (seq listens)
              (call-with-log-appenders logger-id listeners f)
              (let [[listen & remainder] listens]
                (with-started [listener (doto (log-event-listener listen)
                                          .start)]
                  (set-up remainder (conj listeners listener))))))]
    (set-up listens [])))

(defmacro with-log-event-listeners
  "For each listen in listens, calls (listen event) for each logger-id
  event produced during the evaluation of body, after removing any
  existing log appenders.  If logger-id is not a class, it will be
  converted via str."
  [logger-id listens & body]
  `(call-with-log-event-listeners ~logger-id ~listens (fn [] ~@body)))

(defmacro with-additional-logging-to-atom
  "Conjoins all logger-id events produced during the evaluation of the
  body onto the collection in the destination atom.  If logger-id is
  not a class, it will be converted via str."
  [logger-id destination & body]
  `(with-additional-log-event-listeners
     ~logger-id
     [(fn [event#] (swap! ~destination conj event#))]
     ~@body))

(defmacro with-logging-to-atom
  "Conjoins all logger-id events produced during the evaluation of the
  body onto the collection in the destination atom, after removing any
  existing log appenders.  If logger-id is not a class, it will be
  converted via str.  For simple situations, with-logged-event-maps
  may be more convenient."
  [logger-id destination & body]
  `(with-log-event-listeners
     ~logger-id
     [(fn [event#] (swap! ~destination conj event#))]
     ~@body))

(defmacro with-logger-event-maps
  "After removing any existing log appenders, binds event-maps to an
  atom containing a collection, and then appends a map to that
  collection for each event logged to logger-id during the evaluation
  of the body.  See event->map for the map structure.  If logger-id is
  not a class, it will be converted via str."
  [logger-id event-maps & body]
  `(let [dest# (atom [])
         ~event-maps dest#]
     (with-log-event-listeners
       ~logger-id
       [(fn [event#] (swap! dest# conj (event->map event#)))]
       ~@body)))

(defmacro with-logged-event-maps
  "After removing any existing log appenders, binds event-maps to an
  atom containing a collection, and then appends a map to that
  collection for each event logged to root-logger-name during the
  evaluation of the body.  See event->map for the map structure."
  [event-maps & body]
  `(with-logger-event-maps root-logger-name ~event-maps
     ~@body))

(defn- suppressing-file-appender
  [log-path]
  (let [pattern "%-4relative [%thread] %-5level %logger{35} - %msg%n"
        context (LoggerFactory/getILoggerFactory)]
    (doto (FileAppender.)
      (.setFile log-path)
      (.setAppend true)
      (.setEncoder (doto (PatternLayoutEncoder.)
                     (.setPattern pattern)
                     (.setContext context)
                     (.start)))
      (.setContext context)
      (.start))))

(defn call-with-log-suppressed-unless-notable [pred f]
  (let [problem (atom false)
        log-path (kitchensink/absolute-path (fs/temp-file "tk-suppressed" ".log"))]
    (try
      (with-started [appender (suppressing-file-appender log-path)
                     detector (doto (log-event-listener
                                     (fn [event]
                                       (when (pred event)
                                         (reset! problem true))))
                                .start)]
        (with-log-appenders root-logger-name
          [appender detector]
          (f)))
      (finally
        (if @problem
          (binding [*out* *err*]
            (print (slurp log-path))
            (println "From error log:" log-path))
          (fs/delete log-path))))))

(defmacro with-log-suppressed-unless-notable
  "Executes the body with all logging suppressed, and passes every log
  event to pred.  Dumps the full log to *err* along with its path if,
  and only if, any invocation of pred returns a true value, .  Assumes
  that the logging level is already set as desired.  This may not work
  correctly if the system logback config is altered during the
  execution of the body."
  [pred & body]
  `(call-with-log-suppressed-unless-notable ~pred (fn [] ~@body)))

(def ^{:doc "An atom containing a sequence of all of the log event maps
             recorded during an evaluation of with-test-logging."
       :dynamic true
       :private true}
  *test-log-events*
  nil)

(defmacro with-test-logging
  "Creates an environment for the use of the logged? test method."
  [& body]
  `(let [destination# (atom [])]
     (binding [*test-log-events* destination#]
       (with-redefs [pl-log/configure-logger! (fn [& _#])]
         (with-log-level root-logger-name :trace
           (with-logging-to-atom root-logger-name destination#
             ~@body))))))

(defmacro with-test-logging-debug
  "Creates an environment for the use of the logged? test method, and
  arranges for every event map logged within that environment to be
  printed to *err*."
  [& body]
  `(let [destination# (atom [])]
     (binding [*test-log-events* destination#]
       (with-log-level root-logger-name :trace
         (with-log-event-listeners root-logger-name
           [(fn [event#]
              (binding [*out* *err*]
                (println "** Log entry:" (pr-str (event->map event#))))
              (swap! destination# conj event#))]
           ~@body)))))

(s/defn ^{:always-validate true} logged?
  ([msg-or-pred] (logged? msg-or-pred nil))
  ([msg-or-pred :- (s/conditional ifn? (s/pred ifn?)
                                  string? s/Str
                                  :else Pattern)
    maybe-level :- (s/maybe (s/pred #(levels %)))]
   (let [match? (cond (ifn? msg-or-pred) msg-or-pred
                      (string? msg-or-pred) #(= msg-or-pred (:message %))
                      :else #(re-find msg-or-pred (:message %)))
         one-element? #(and (seq %) (empty? (rest %)))
         correct-level? #(or (nil? maybe-level) (= maybe-level (:level %)))]
     (->> (map event->map @*test-log-events*)
          (filter correct-level?)
          (filter match?)
          (one-element?)))))

(defmethod clojure.test/assert-expr 'logged? [is-msg form]
  "Asserts that exactly one event in *test-log-events* has a message
  that matches msg-or-pred.  The match is performed via = if
  msg-or-pred is a string, via re-find if msg-or-pred is a pattern, or
  via (msg-or-pred event-map) if msg-or-pred is a function.  If level
  is specified, the message's keyword level (:info, :error, etc.) must
  also match.  For example:
    (with-test-logging (log/info \"123\") (is (logged? #\"2\")))."
  (assert (#{2 3} (count form)))
  (let [[_ msg-or-pred level] form]
    `(let [events# @@#'puppetlabs.trapperkeeper.testutils.logging/*test-log-events*]
       (if-not (logged? ~msg-or-pred ~level)
         (clojure.test/do-report
          {:type :fail
           :message ~is-msg
           :expected '~form
           :actual (list '~'logged events#)})
         (clojure.test/do-report
          {:type :pass
           :message ~is-msg
           :expected '~form
           :actual (list '~'logged events#)})))))

(defn reset-logging-config-after-test
  "Fixture that will reset the logging configuration after each
  test.  Useful for tests that manipulate the logging configuration,
  in order to ensure that they don't affect test logging for subsequent
  tests."
  [f]
  (f)
  (pl-log/reset-logging))


;;; Deprecated API (clojure.tools.logging specific, etc.)

(def ^{:doc "A dynamic var that is bound to an atom containing all of the log entries
             that have occurred during a test, when using `with-test-logging`."
       :dynamic true
       :deprecated "1.1.2"}
  *test-logs*
  nil)

(def ^{:deprecated "1.1.2"} legal-levels
  #{nil :trace :debug :info :warn :error :fatal})

(defn- ^{:deprecated "1.1.2"} log-entry->map
  [log-entry]
  {:namespace (get log-entry 0)
   :level     (get log-entry 1)
   :exception (get log-entry 2)
   :message   (get log-entry 3)})

(defn ^{:deprecated "1.1.2"} logs-matching
  "Given a regular expression pattern, a sequence of log messages (in the format
  used by `clojure.tools.logging`, and (optionally) a log level (as a keyword
  that corresponds to those in `clojure.tools.logging`) return only the logs
  whose message matches the specified regular expression pattern that were
  logged at the given level (or at any level if not specified). (Intended to
  be used alongside `with-log-output` for tests that are validating log
  output.) The result is a sequence of maps, each of which contains the
  following keys: `:namespace`, `:level`, `:exception`, and `:message`."
  ([pattern logs] (logs-matching pattern logs nil))
  ([pattern logs level]
   {:pre  [(instance? java.util.regex.Pattern pattern)
           (coll? logs)
           #_:clj-kondo/ignore
           (contains? legal-levels level)]}
   ;; the logs are formatted as sequences, where the keyword at index 1
   ;; contains the level and the string at index 3 contains the actual
   ;; log message.
   (let [matches-level? (fn [log-entry] (or (nil? level) (= level (get log-entry 1))))
         matches-msg? (fn [log-entry] (re-find pattern (get log-entry 3)))
         matches (filter #(and (matches-level? %) (matches-msg? %)) logs)]
     #_:clj-kondo/ignore
     (map log-entry->map matches))))

(defn ^{:deprecated "1.1.2"} log-to-console
  "Utility function called by atom-logger and atom-appender to log entries to the
  console when running in debug mode."
  [entry]
  (println "** Log entry:" entry))

(defn ^{:deprecated "1.1.2"} atom-logger
  "Returns a logger factory that returns loggers that conjoin each log
  event onto the collection in the destination atom, and that also
  invoke (log-to-console event) if debug? is true.  This will only
  capture events logged by clojure.tools.logging, and the events will
  be vectors like this [namespace level exception message].  Prefer
  with-logging-to-atom."
  ([destination] (atom-logger destination false))
  ([destination debug?]
   (reify impl/LoggerFactory
     (name [_] "test factory")
     (get-logger [_ log-ns]
       (reify impl/Logger
         (enabled? [_ _level] true)
         (write! [_ lvl ex msg]
           (let [entry [(str log-ns) lvl ex msg]]
             #_:clj-kondo/ignore
             (when debug? (log-to-console entry))
             (swap! destination conj entry)
             nil)))))))

(defn ^{:deprecated "1.1.2"} atom-appender
  "Returns a log Appender that conjoins each log event to the
  collection in the destination atom, and that also
  invokes (log-to-console event) if debug? is true.  Prefer
  with-logging-to-atom."
  ([destination] (atom-appender destination false))
  ([destination debug?]
   (let [appender (proxy [AppenderBase] []
                    (append [logging-event]
                      (let [throwable-info (.getThrowableInformation logging-event)
                            ex (when throwable-info (.getThrowable throwable-info))
                            entry [(.getLoggerName logging-event)
                                   (.getLevel logging-event)
                                   ex
                                   (str (.getFormattedMessage logging-event))]]
                        #_:clj-kondo/ignore
                        (when debug? (log-to-console entry))
                        (swap! destination conj entry)))
                    (close []))]
     (.setContext appender (pl-log/logging-context))
     appender)))

(defmacro ^{:deprecated "1.1.2"} with-log-output-atom
  "This is a utility macro, intended for use by other macros such as
  `with-test-logging`.

  Given an atom whose value is a sequence, sets up a temporary logger to capture
  all log output to the sequence, and evaluates `body` in this logging context.

  `log-output-atom` - Inside of `body`, this atom will be used to store
  the sequence of log messages that have been logged so far.  You can access the
  individual log messages by dereferencing the atom.

  Prefer with-logging-to-atom."
  [log-output-atom options & body]
  `(let [root-logger#     (pl-log/root-logger)
         orig-appenders#  (vec (iterator-seq (.iteratorForAppenders root-logger#)))
         orig-started#    (into {} (map #(vector % (.isStarted %)) orig-appenders#))
         temp-appender#  (atom-appender ~log-output-atom (~options :debug))]
     (.setName temp-appender# "testutils-temp-log-appender")
     (try
       (doseq [orig-appender# orig-appenders#]
         #_:clj-kondo/ignore
         (.stop orig-appender#))
       (.addAppender root-logger# temp-appender#)

       (binding [clojure.tools.logging/*logger-factory*
                 (atom-logger
                  ~log-output-atom
                  (~options :debug))]
         ~@body)
       (finally
         (.detachAppender root-logger# temp-appender#)
         (doseq [orig-appender# orig-appenders#]
           (if (orig-started# orig-appender#)
             (.start orig-appender#)))))))

(defmacro ^{:deprecated "1.1.2"} with-log-output
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
