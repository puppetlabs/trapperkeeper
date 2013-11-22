(ns puppetlabs.trapperkeeper.core
  (:import (java.io FileNotFoundException))
  (:require [plumbing.graph :as graph]
            [plumbing.core :refer [fnk]]
            [plumbing.fnk.pfnk :refer [input-schema output-schema fn->fnk]]
            [clojure.java.io :refer [file]]
            [puppetlabs.kitchensink.core :refer [add-shutdown-hook! boolean? inis-to-map]]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.logging :refer [configure-logging!]]
            [puppetlabs.trapperkeeper.utils :refer [service-graph? walk-leaves-and-path]]))

;  A type representing a trapperkeeper application.  This is intended to provide
;  an abstraction so that users don't need to worry about the implementation
;  details and can pass the app object to our functions in a type-safe way.
;  The internal properties are not intended to be used outside of this
;  namespace.
(defrecord TrapperKeeperApp [graph-instance])

(defn get-service-fn
  "Given a trapperkeeper application, a service name, and a sequence of keys,
  returns the function provided by the service at that path.

  Example usage: (get-service-fn app :my-service :do-something-awesome)"
  [^TrapperKeeperApp app service k & ks]
  {:pre [(keyword? service)
         (keyword? k)
         (every? keyword? ks)]
   :post [(not (nil? %))
          (ifn? %)]}
  (get-in (:graph-instance app) (cons service (cons k ks))))

(defn- io->fnk-binding-form
  "Converts a service's input-output map into a binding-form suitable for
  passing to a fnk. The binding-form defines the fnk's expected input and
  output values, and is required to satisfy graph compilation.

  This function is necessary in order to allow for the defservice macro to
  support arbitrary code in the body. A fnk will attempt to determine what
  its output-schema is, but will only work if a map is immediately returned
  from the body. When a map is not immediately returned (i.e. a `let` block
  around the map), the output-schema must be explicitly provided in the fnk
  metadata."
  [io-map]
  (let [to-output-schema  (fn [provides]
                            (reduce (fn [m p] (assoc m (keyword p) true))
                                    {}
                                    provides))
        output-schema     (to-output-schema (:provides io-map))]
    ;; Add an output-schema entry to the depends vector's metadata map
    (vary-meta (:depends io-map) assoc :output-schema output-schema)))

(defmacro service
  "Define a service that may depend on other services, and provides functions
  for other services to depend on. This macro is intended to be used inline
  rather than at the top-level (see `defservice` for that).

  Defining a service requires a:
    * service name keyword
    * input-output map in the form: {:depends [...] :provides [...]}
    * a body of code that returns a map of functions the service provides.
      The keys of the map must match the values of the :provides vector.

  Example:

    (service :logging-service
      {:depends  []
       :provides [log]}
      {:log (fn [msg] (println msg))})"
  [svc-name io-map & body]
  (let [binding-form (io->fnk-binding-form io-map)]
    `(fn []
       {~svc-name
        (fnk
          ~binding-form
          ~@body)})))

(defmacro defservice
  "Define a service that may depend on other services, and provides functions
  for other services to depend on. Defining a service requires a:
    * service name
    * optional documentation string
    * input-output map in the form: {:depends [...] :provides [...]}
    * a body of code that returns a map of functions the service provides.
      The keys of the map must match the values of the :provides vector.

  Examples:

    (defservice logging-service
      {:depends  []
       :provides [debug info warn]}
      {:debug (partial println \"DEBUG:\")
       :info  (partial println \"INFO:\")
       :warn  (partial println \"WARN:\")})

    (defservice datastore-service
      \"Store key-value pairs.\"
      {:depends  [[:logging-service debug]]
       :provides [get put]}
      (let [log       (partial debug \"[datastore]\")
            datastore (atom {})]
        {:get (fn [key]       (log \"Getting...\") (get @datastore key))
         :put (fn [key value] (log \"Putting...\") (swap! datastore assoc key value))}))"
  [svc-name & forms]
  (let [[svc-doc io-map body] (if (string? (first forms))
                                [(first forms) (second forms) (nthrest forms 2)]
                                ["" (first forms) (rest forms)])]
    `(def ~svc-name
       ~svc-doc
       (service ~(keyword svc-name) ~io-map ~@body))))

(defn- cli-service
  "Provides access to the command-line arguments for other services."
  [cli-data]
  ((service :cli-service
    {:depends  []
     :provides [cli-data]}
    {:cli-data (fn
                 ([] cli-data)
                 ([k] (cli-data k)))})))

(defn config-service
  "A simple configuration service based on .ini config files.  Expects
   to find a command-line argument value for `:config` (which it will
   retrieve from the `:cli-service`'s `cli-data` fn); the value of this
   parameter should be the path to an .ini file or a directory of .ini
   files.

   Provides a function, `get-in-config`, which can be used to
   retrieve the config data read from the ini files.  For example,
   given an ini file with the following contents:

       [foo]
       bar = baz

   The value of `(get-in-config [:foo :bar])` would be `\"baz\"`.

   Also provides a second function, `get-config`, which simply returns
   the entire configuration map."
  [config]
  ((service :config-service
            {:depends []
             :provides [get-in-config get-config]}
            {:get-in-config (fn [ks] (get-in config ks))
             :get-config (fn [] config)})))

(defn- parse-config-file
  [config-file-path]
  {:pre [(not (nil? config-file-path))]
   :post [(map? %)]}
  (when-not (.canRead (file config-file-path))
    (throw (FileNotFoundException.
             (format
               "Configuration path '%s' must exist and must be readable."
               config-file-path))))
  (inis-to-map config-file-path))

(defn- wrap-with-shutdown-registration
  "Given an accumulating list of shutdown functions and a path to a service
  in the graph, extract the shutdown function from the service and add it to
  the list."
  [shutdown-fns-atom path orig-fnk]
  (let [in  (input-schema orig-fnk)
        out (output-schema orig-fnk)
        f   (fn [injected-vals]
              (let [result (orig-fnk injected-vals)]
                (when-let [shutdown-fn (result :shutdown)]
                  (swap! shutdown-fns-atom conj shutdown-fn))
                result))]
    (fn->fnk f [in out])))

(defn- register-shutdown-hooks!
  "Walk the graph and register all shutdown functions. The functions
  will be called when the JVM shuts down, or by calling `shutdown!`."
  [graph]
  (let [shutdown-fns      (atom ())
        wrapped-graph     (walk-leaves-and-path
                            (partial wrap-with-shutdown-registration shutdown-fns)
                            graph)
        do-shutdown-fn    #(doseq [f @shutdown-fns] (f))
        shutdown-service  (service :shutdown-service
                                   {:depends  []
                                    :provides [do-shutdown]}
                                   {:do-shutdown do-shutdown-fn})]
    (add-shutdown-hook! do-shutdown-fn)
    (merge (shutdown-service) wrapped-graph)))

(defn shutdown!
  "Perform shutdown on the application by calling all service shutdown hooks.
  Services will be shut down in dependency order."
  [^TrapperKeeperApp app]
  ((get-service-fn app :shutdown-service :do-shutdown)))

(defn- compile-graph
  "Given the merged map of services, compile it into a function suitable for instantiation.
  Throws an exception if there is a dependency on a service that is not found in the map."
  [graph-map]
  {:pre  [(service-graph? graph-map)]
   :post [(ifn? %)]}
  (try
    (graph/eager-compile graph-map)
    (catch IllegalArgumentException e
      (let [match (re-matches #"(?s)^Failed on keyseq: \[:(.*)\]\. Value is missing\. .*$" (.getMessage e))]
        (if match
          (throw (RuntimeException. (format "Service function '%s' not found" (second match))))
          (throw e))))))

(defn- instantiate
  "Given the compiled graph function, instantiate the application. Throws an exception
  if there is a dependency on a service function that is not found in the graph."
  [graph-fn]
  {:pre  [(ifn? graph-fn)]
   :post [(service-graph? %)]}
  (try
    (graph-fn {})
    (catch RuntimeException e
      (if-let [match (re-matches #"^Key (:.*) not found in null$" (.getMessage e))]
        (throw (RuntimeException. (format "Service '%s' not found" (second match))))
        (if-let [match (re-matches #"^Key :(.*) not found in .*$" (.getMessage e))]
          (throw (RuntimeException. (format "Service function '%s' not found" (second match))))
          (throw e))))))

(defn bootstrap*
  "Helper function for bootstrapping a trapperkeeper app."
  ([services cli-data]
  {:pre [(sequential? services)
         (every? service-graph? services)
         (map? cli-data)]
   :post [(instance? TrapperKeeperApp %)]}
  (let [cli-service     (cli-service cli-data)
        config-data     (parse-config-file (cli-data :config))
        config-service  (config-service config-data)
        _               (if-let [global-config (config-data :global)]
                          (configure-logging! (global-config :logging-config) (cli-data :debug)))
        graph-map       (-> (apply merge cli-service config-service services)
                            (register-shutdown-hooks!))
        graph-fn        (compile-graph graph-map)
        graph-instance  (instantiate graph-fn)]
    (TrapperKeeperApp. graph-instance))))

(defn bootstrap
  "Bootstrap a trapperkeeper application.  This is accomplished by reading a
  bootstrap configuration file containing a list of (namespace-qualified)
  service functions.  These functions will be called to generate a service
  graph for the application; dependency resolution between the services will
  be handled automatically to ensure that they are started in the correct order.
  Functions that a service expresses dependencies on will be injected prior to
  instantiation of a service.

  The bootstrap config file will be searched for in this order:

  * At a path specified by the optional command-line argument `--bootstrap-config`
  * In the current working directory, in a file named `bootstrap.cfg`
  * On the classpath, in a file named `bootstrap.cfg`.

  `cli-data` is a map of the command-line arguments and their values.
  `puppetlabs.kitchensink/cli!` can handle the parsing for you.

  Their must be a `:config` key in this map which defines the .ini file
  (or directory of files) used by the configuration service."
  [cli-data]
  {:pre [(map? cli-data)
         (contains? cli-data :config)]
  :post [(instance? TrapperKeeperApp %)]}
  (if-let [bootstrap-config (or (bootstrap/config-from-cli! cli-data)
                                  (bootstrap/config-from-cwd)
                                  (bootstrap/config-from-classpath))]
    (-> bootstrap-config
        (bootstrap/parse-bootstrap-config!)
        (bootstrap* cli-data))
    (throw (IllegalStateException.
             "Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath"))))
