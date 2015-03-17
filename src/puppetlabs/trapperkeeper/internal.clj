(ns puppetlabs.trapperkeeper.internal
  (:import (clojure.lang Atom ExceptionInfo))
  (:require [clojure.tools.logging :as log]
            [plumbing.graph :as graph]
            [puppetlabs.kitchensink.core :refer [add-shutdown-hook! boolean? cli!]]
            [puppetlabs.trapperkeeper.config :refer [config-service]]
            [puppetlabs.trapperkeeper.app :as a]
            [puppetlabs.trapperkeeper.services :as s]
            [puppetlabs.kitchensink.core :as ks]))

(defn service-graph?
  "Predicate that tests whether or not the argument is a valid trapperkeeper
  service graph."
  [service-graph]
  (and
    (map? service-graph)
    (every? keyword? (keys service-graph))
    (every? (some-fn ifn? service-graph?) (vals service-graph))))

(defn validate-service-graph!
  "Validates that a ServiceDefinition contains a valid trapperkeeper service graph.
  Returns the service definition on success; throws an IllegalArgumentException
  if the graph is invalid."
  [service-def]
  {:post [(satisfies? s/ServiceDefinition %)]}
  (if-not (satisfies? s/ServiceDefinition service-def)
    (throw (IllegalArgumentException.
             (str "Invalid service definition; expected a service "
                  "definition (created via `service` or `defservice`); "
                  "found: " (pr-str service-def)))))
  (if (service-graph? (s/service-map service-def))
    service-def
    (throw (IllegalArgumentException. (str "Invalid service graph; service graphs must "
                                           "be nested maps of keywords to functions.  Found: "
                                           (s/service-map service-def))))))

(defn parse-missing-required-key
  "Prismatic's graph compilation code throws `ExceptionInfo` objects if required
  keys are missing somewhere in the graph structure.  This includes a map with
  information about what keys were missing.  This function is responsible for
  interpreting one of those error maps to determine whether it implies that the
  trapperkeeper service definition was missing a service function.

  Returns a map containing the name of the service and the name of the missing
  function, or nil if the error map looks like it represents some other kind
  of error."
  [m]
  {:pre [(map? m)]
   :post [(or (nil? %)
              (and (map? %)
                   (contains? % :service-name)
                   (contains? % :service-fn)))]}
  (let [service-name    (first (keys m))
        service-fn-name (first (keys (m service-name)))
        error           (get-in m [service-name service-fn-name])]
    (when (= error 'missing-required-key)
      {:service-name (name service-name)
       :service-fn   (name service-fn-name)})))

(defn handle-prismatic-exception!
  "Takes an ExceptionInfo object that was thrown during a prismatic graph
  compilation / instantiation, and inspects it to see if the error data map
  represents a missing trapperkeeper service or function.  If so, throws a
  more meaningful exception.  If not, re-throws the original exception."
  [e]
  {:pre [(instance? ExceptionInfo e)]}
  (let [data (ex-data e)]
    (condp = (:error data)
      :missing-key
      (throw (RuntimeException. (format "Service '%s' not found" (:key data))))

      :does-not-satisfy-schema
      (if-let [error-info (parse-missing-required-key (:failures data))]
        (throw (RuntimeException. (format "Service function '%s' not found in service '%s'"
                                          (:service-fn error-info)
                                          (:service-name error-info))))
        (throw e))

      (if (sequential? (:error data))
        (let [missing-services (keys (ks/filter-map
                                       (fn [_ v] (= v 'missing-required-key))
                                       (.error (first (:error data)))))]
          (if (= 1 (count missing-services))
            (throw (RuntimeException.
                     (format "Service '%s' not found" (first missing-services))))
            (throw (RuntimeException.
                     (format "Services '%s' not found" missing-services)))))
        (throw e)))))

(defn compile-graph
  "Given the merged map of services, compile it into a function suitable for instantiation.
  Throws an exception if there is a dependency on a service that is not found in the map."
  [graph-map]
  {:pre  [(service-graph? graph-map)]
   :post [(ifn? %)]}
  (try
    (graph/eager-compile graph-map)
    (catch ExceptionInfo e
      (handle-prismatic-exception! e))))

(defn instantiate
  "Given the compiled graph function, instantiate the application. Throws an exception
  if there is a dependency on a service function that is not found in the graph."
  [graph-fn data]
  {:pre  [(ifn? graph-fn)
          (map? data)]
   :post [(service-graph? %)]}
  (try
    (graph-fn data)
    (catch ExceptionInfo e
      (handle-prismatic-exception! e))))


(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
  Hard-codes the command-line arguments expected by trapperkeeper to be:
      --debug
      --bootstrap-config <bootstrap file>
      --config <.ini file or directory>
      --plugins <plugins directory>"
  [cli-args]
  {:pre  [(sequential? cli-args)]
   :post [(map? %)]}
  (let [specs       [["-d" "--debug" "Turns on debug mode"]
                     ["-b" "--bootstrap-config BOOTSTRAP-CONFIG-FILE" "Path to bootstrap config file"]
                     ["-c" "--config CONFIG-PATH"
                      (str "Path to a configuration file or directory of configuration files, "
                           "or a comma-separated list of such paths."
                           "See the documentation for a list of supported file types.")]
                     ["-p" "--plugins PLUGINS-DIRECTORY" "Path to directory plugin .jars"]]
        required    []]
    (first (cli! cli-args specs required))))

(defn run-lifecycle-fn!
  "Run a lifecycle function for a service.  Required arguments:

  * app-context: the app context atom; can be updated by the lifecycle fn
  * lifecycle-fn: a fn from the Lifecycle protocol
  * lifecycle-fn-name: a string containing the name of the lifecycle fn that
                       is being run.  This is only used to produce a readable
                       error message if an error occurs.
  * service-id: the id of the service that the lifecycle fn is being run on
  * s: the service that the lifecycle fn is being run on"
  [app-context lifecycle-fn lifecycle-fn-name service-id s]
  {:pre [(instance? Atom app-context)
         (ifn? lifecycle-fn)
         (string? lifecycle-fn-name)
         (keyword? service-id)
         (satisfies? s/Lifecycle s)]}
  (let [;; call the lifecycle function on the service, and keep a reference
        ;; to the updated context map that it returns
        updated-ctxt  (lifecycle-fn s (get @app-context service-id {}))]
    (if-not (map? updated-ctxt)
      (throw (IllegalStateException.
               (format
                 "Lifecycle function '%s' for service '%s' must return a context map (got: %s)"
                 lifecycle-fn-name
                 (or (s/service-symbol s) service-id)
                 (pr-str updated-ctxt)))))
    ;; store the updated service context map in the application context atom
    (swap! app-context assoc service-id updated-ctxt)))

(defn run-lifecycle-fns
  "Run a lifecycle function for all services.  Required arguments:

  * app-context: the app context atom; can be updated by the lifecycle fn
  * lifecycle-fn: a fn from the Lifecycle protocol
  * lifecycle-fn-name: a string containing the name of the lifecycle fn that
                       is being run.  This is only used to produce a readable
                       error message if an error occurs.
  * ordered-services: a list of the services in an order that conforms to
                      their dependency specifications.  This ensures that
                      we call the lifecycle functions in the correct order
                      (i.e. a service can be assured that any services it
                      depends on will have their corresponding lifecycle fn
                      called first.)"
  [app-context lifecycle-fn lifecycle-fn-name ordered-services]
  (try
    (doseq [[service-id s] ordered-services]
      (run-lifecycle-fn! app-context
                         lifecycle-fn
                         lifecycle-fn-name
                         service-id
                         s))
    (catch Throwable t
      (log/errorf t "Error during service %s!!!" lifecycle-fn-name)
      (throw t))))

;;;; Application Shutdown Support
;;;;
;;;; The next section of this namespace
;;;; provides top-level functions to be called by TrapperKeeper internally
;;;; and a shutdown service for application services to utilize.
;;;;
;;;; Performing the actual shutdown is relatively easy, but dealing with
;;;; exceptions during shutdown and providing various ways for services
;;;; to trigger shutdown make up most of the code in this namespace.
;;;;
;;;; During development of this namespace we wanted to ensure that the
;;;; shutdown sequence occurred on the main thread and not on some
;;;; service's worker thread. Because of this, a Clojure `promise` is
;;;; used to pass contextual shutdown information back to the main thread,
;;;; at which point it is responsible for calling back into this namespace
;;;; to perform the appropriate shutdown steps.
;;;;
;;;; As a consequence of the implementation, the main blocking behavior of
;;;; TrapperKeeper is defined here by `wait-for-app-shutdown`. This function
;;;; simply attempts to dereference the above mentioned promise, which
;;;; until it is realized (e.g. by a call to `deliver`) will block, and the
;;;; delivered value returned. This value will contain contextual information
;;;; regarding the cause of the shutdown, and is intended to be passed back
;;;; in to the top-level functions that perform various shutdown steps.

(def ^{:private true
       :doc "The possible causes for shutdown to be initiated."}
  shutdown-causes #{:requested :service-error :jvm-shutdown-hook})

(defn request-shutdown*
  "Initiate the normal shutdown of TrapperKeeper. This is asynchronous.
  It is assumed that `wait-for-app-shutdown` has been called and is blocking.
  Intended to be used by application services (likely their worker threads)
  to programatically trigger application shutdown.
  Note that this is exposed via the `shutdown-service` where it is a no-arg
  function."
  [shutdown-reason-promise]
  (deliver shutdown-reason-promise {:cause :requested}))

(defn shutdown-on-error*
  "A higher-order function that is intended to be used as a wrapper around
  some logic `f` in your services. It will wrap your application logic in a
  `try/catch` block that will cause TrapperKeeper to initiate an error shutdown
  if an exception occurs in your block. This is generally intended to be used
  on worker threads that your service may launch.

  If an optional `on-error-fn` is provided, it will be executed if `f` throws
  an exception, but before the primary shutdown sequence begins."
  ([shutdown-reason-promise app-context svc-id f]
   (shutdown-on-error* shutdown-reason-promise app-context svc-id f nil))
  ([shutdown-reason-promise app-context svc-id f on-error-fn]
   (try
     ; These would normally be pre-conditions; however, this function needs
     ; to never throw an exception - since it is often called as a wrapper
     ; around everything inside a `future`, it is important that this function
     ; never throw anything (like an AssertionError from a pre-condition),
     ; since it is likely to just get lost in a `future`.  Instead,
     ; invalid arguments will simply cause the shutdown promise to be delivered.
     (assert (instance? Atom app-context))
     (assert (map? @app-context))
     (assert (keyword? svc-id))
     (assert (contains? @app-context svc-id))
     (assert (ifn? f))
     (assert ((some-fn nil? ifn?) on-error-fn))

     (f)
     (catch Throwable t
       (log/error t "shutdown-on-error triggered because of exception!")
       (deliver shutdown-reason-promise {:cause       :service-error
                                         :error       t
                                         :on-error-fn (when on-error-fn
                                                        (partial on-error-fn (get @app-context svc-id)))})))))

(defprotocol ShutdownService
  (get-shutdown-reason [this])
  (wait-for-shutdown [this])
  (request-shutdown [this] "Asynchronously trigger normal shutdown")
  (shutdown-on-error [this svc-id f] [this svc-id f on-error]
    "Higher-order function to execute application logic and trigger shutdown in
    the event of an exception"))

(defn- shutdown-service
  "Provides various functions for triggering application shutdown programatically.
  Primarily intended to serve application services, though TrapperKeeper also uses
  this service internally and therefore is always available in the application graph.

  Provides:
  * :get-shutdown-reason - Get a map containing the shutdown reason delivered
                           to the shutdown service.  If no shutdown reason has
                           been delivered, this function would return nil.
  * :wait-for-shutdown   - Block the calling thread until a shutdown reason
                           has been delivered to the shutdown service
  * :request-shutdown    - Asynchronously trigger normal shutdown
  * :shutdown-on-error   - Higher-order function to execute application logic
                           and trigger shutdown in the event of an exception

  For more information, see `request-shutdown` and `shutdown-on-error`."
  [shutdown-reason-promise app-context]
  (s/service ShutdownService
    []
    (get-shutdown-reason [this] (when (realized? shutdown-reason-promise)
                                      @shutdown-reason-promise))
    (wait-for-shutdown [this] (deref shutdown-reason-promise))
    (request-shutdown [this]  (request-shutdown* shutdown-reason-promise))
    (shutdown-on-error [this svc-id f] (shutdown-on-error* shutdown-reason-promise app-context svc-id f))
    (shutdown-on-error [this svc-id f on-error] (shutdown-on-error* shutdown-reason-promise app-context svc-id f on-error))))

(defn ordered-services?
  "Predicate that validates that an object is an ordered list of services as required
  for the app context map.  Each item in the list must be a tuple whose first element
  is a service id (keyword) and whose second element is the service instance."
  [os]
  (or (nil? os)
      (and
        (sequential? os)
        (every? vector? os)
        (every? #(= (count %) 2) os)
        (every? #(keyword? (first %)) os)
        (every? #(satisfies? s/Lifecycle (second %)) os))))

(defn shutdown!
  "Perform shutdown calling the `stop` lifecycle function on each service,
   in reverse order (to account for dependency relationships)."
  [app-context]
  {:pre [(instance? Atom app-context)
         (let [ctxt @app-context]
           (and (map? ctxt)
                (ordered-services? (ctxt :ordered-services))))]}
  (log/info "Beginning shutdown sequence")
  (doseq [[service-id s] (reverse (@app-context :ordered-services))]
    (try
      (run-lifecycle-fn! app-context s/stop "stop" service-id s)
      (catch Exception e
        (log/error e "Encountered error during shutdown sequence"))))
  (log/info "Finished shutdown sequence"))

(defn initialize-shutdown-service!
  "Initialize the shutdown service and add a shutdown hook to the JVM."
  [app-context shutdown-reason-promise]
  {:pre [(instance? Atom app-context)]
   :post [(satisfies? s/ServiceDefinition %)]}
  (let [shutdown-service        (shutdown-service shutdown-reason-promise
                                                  app-context)]
    (add-shutdown-hook! (fn []
                          (when-not (realized? shutdown-reason-promise)
                            (log/info "Shutting down due to JVM shutdown hook.")
                            (shutdown! app-context)
                            (deliver shutdown-reason-promise {:cause :jvm-shutdown-hook}))))
    shutdown-service))

(defn get-app-shutdown-reason
  "Get a map containing the shutdown reason delivered to the shutdown service.
  If no shutdown reason has been delivered, this function would return nil.

  If non-nil, the reason map may contain the following keys:
  * :cause       - One of :requested, :service-error, or :jvm-shutdown-hook
  * :error       - The error associated with the :service-error cause
  * :on-error-fn - An optional error callback associated with the :service-error
                   cause"
  [app]
  {:pre [(satisfies? a/TrapperkeeperApp app)]
   :post [(or (nil? %) (map? %))]}
  (get-shutdown-reason (a/get-service app :ShutdownService)))

(defn throw-app-error-if-exists!
  "For the supplied app, attempt to pull out a shutdown reason error.  If
  one is available, throw a Throwable with that error.  If not, just return
  the app instance that was provided."
  [app]
  {:pre [(satisfies? a/TrapperkeeperApp app)]
   :post [(identical? app %)]}
  (when-let [shutdown-reason (get-app-shutdown-reason app)]
    (if-let [shutdown-error (:error shutdown-reason)]
      (throw shutdown-error)))
  app)

(defn wait-for-app-shutdown
  "Wait for shutdown to be initiated - either externally (such as Ctrl-C) or
  internally (requested by service or service error) - and return the reason.

  The reason map may contain the following keys:
  * :cause       - One of :requested, :service-error, or :jvm-shutdown-hook
  * :error       - The error associated with the :service-error cause
  * :on-error-fn - An optional error callback associated with the :service-error cause"
  [app]
  {:pre [(satisfies? a/TrapperkeeperApp app)]
   :post [(map? %)]}
  (wait-for-shutdown (a/get-service app :ShutdownService)))

(defn initiated-internally?
  "Given the shutdown reason obtained from `wait-for-shutdown`, determine whether
  the cause was internal so further shutdown steps can be performed.
  In the case of an externally-initiated shutdown (e.g. Ctrl-C), we assume that
  the JVM shutdown hook will perform the actual shutdown sequence as we don't want
  to rely on control returning to the main thread where it can perform shutdown."
  [shutdown-reason]
  {:pre  [(map? shutdown-reason)]
   :post [(boolean? %)]}
  (contains? (disj shutdown-causes :jvm-shutdown-hook) (:cause shutdown-reason)))

(defn call-error-handler!
  "Given the shutdown reason obtained from `wait-for-shutdown`, call the
  error callback that was provided to `shutdown-on-error`, if there is one.
  An error will be logged if the function throws an exception."
  [shutdown-reason]
  {:pre [(map? shutdown-reason)]}
  (when-let [on-error-fn (:on-error-fn shutdown-reason)]
    (try
      (on-error-fn)
      (catch Throwable t
        (log/error t "Error occurred during shutdown")))))

;;;; end of shutdown-related functions

(defn build-app*
  "Given a list of services and a map of configuration data, build an instance
  of a TrapperkeeperApp.  Services are not yet initialized or started."
  [services config-data shutdown-reason-promise]
  {:pre  [(sequential? services)
          (every? #(satisfies? s/ServiceDefinition %) services)
          (map? config-data)]
   :post [(satisfies? a/TrapperkeeperApp %)]}
  (let [;; this is the application context for this app instance.  its keys
        ;; will be the service ids, and values will be maps that represent the
        ;; context for each individual service
        app-context (atom {})
        service-refs (atom {})
        services (conj services
                       (config-service config-data)
                       (initialize-shutdown-service! app-context
                                                     shutdown-reason-promise))
        service-map (apply merge (map s/service-map services))
        compiled-graph (compile-graph service-map)
        ;; this gives us an ordered graph that we can use to call lifecycle
        ;; functions in the correct order later
        graph (graph/->graph service-map)
        ;; when we instantiate the graph, we pass in the context atom.
        graph-instance (instantiate compiled-graph {:tk-app-context app-context
                                                    :tk-service-refs service-refs})
        ;; dereference the atom of service references, since we don't need to update it
        ;; any further
        services-by-id @service-refs
        ordered-services (map (fn [[service-id _]] [service-id (services-by-id service-id)]) graph)]
    (swap! app-context assoc :services-by-id services-by-id)
    (swap! app-context assoc :ordered-services ordered-services)
    (doseq [svc-id (keys services-by-id)] (swap! app-context assoc svc-id {}))
    ;; finally, create the app instance
    (reify
      a/TrapperkeeperApp
      (a/get-service [this protocol] (services-by-id (keyword protocol)))
      (a/service-graph [this] graph-instance)
      (a/app-context [this] app-context)
      (a/check-for-errors! [this] (throw-app-error-if-exists!
                                    this))
      (a/init [this]
        (run-lifecycle-fns app-context s/init "init" ordered-services)
        this)
      (a/start [this]
        (run-lifecycle-fns app-context s/start "start" ordered-services)
        this)
      (a/stop [this]
        (shutdown! app-context)
        this))))

(defn boot-services*
  "Given the services to run and the map of configuration data, create the
  TrapperkeeperApp and boot the services.  Returns the TrapperkeeperApp."
  [services config-data]
  {:pre  [(sequential? services)
          (every? #(satisfies? s/ServiceDefinition %) services)
          (map? config-data)]
   :post [(satisfies? a/TrapperkeeperApp %)]}
  (let [shutdown-reason-promise (promise)
        app                     (try
                                  (build-app* services
                                              config-data
                                              shutdown-reason-promise)
                                  (catch Throwable t
                                    (log/error t "Error during app buildup!")
                                    (throw t)))]
      (try
        (a/init app)
        (a/start app)
        (catch Throwable t
          (deliver shutdown-reason-promise {:cause :service-error
                                            :error t})))
      app)
    )


