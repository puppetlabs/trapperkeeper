(ns puppetlabs.trapperkeeper.core
  (:require [clojure.java.io :refer [IOFactory]]
            [plumbing.graph :as graph]
            [plumbing.core :refer [fnk]]
            [puppetlabs.utils :refer [cli!]]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]))

;  A type representing a trapperkeeper application.  This is intended to provide
;  an abstraction so that users don't need to worry about the implementation
;  details and can pass the app object to our functions in a type-safe way.
;  The internal properties are not intended to be used outside of this
;  namespace.
(defrecord TrapperKeeperApp [graph-instance])

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.utils/cli!`.
  Hard-codes the command-line arguments expected by trapperkeeper to be:
      --debug
      --bootstrap-config <bootstrap file>
      --config <.ini file or directory>"
  [cli-args]
  (let [specs       [["-d" "--debug" "Turns on debug mode" :flag true]
                     ["-b" "--bootstrap-config" "Path to bootstrap config file (optional)"]
                     ["-c" "--config" "Path to .ini file or directory of .ini files to be read and consumed by services (optional)"]]
        required    []]
    (first (cli! cli-args specs required))))

(defn- cli-service
  "The 'service' that provides command-line argument access to other services.
  It is really just a `fnk` that always ends up in the service graph so that
  services can access the command-line arguments"
  [cli-data]
  {:cli-service
   (fnk []
     {:cli-data (fn
                  ([] cli-data)
                  ([k] (cli-data k)))})})

(defn bootstrap*
  "Helper function for bootstrapping a trapperkeeper app."
  ([bootstrap-config] (bootstrap* bootstrap-config {}))
  ([bootstrap-config cli-data]
  {:pre [(satisfies? IOFactory bootstrap-config)
         (map? cli-data)]
   :post [(instance? TrapperKeeperApp %)]}
  (let [graph-map       (merge (cli-service cli-data) (bootstrap/parse-bootstrap-config! bootstrap-config))
        graph-fn        (graph/eager-compile graph-map)
        graph-instance  (graph-fn {})
        app             (TrapperKeeperApp. graph-instance)]
    app)))

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
  * In the current working directory, in a file named `bootstrap.cfg`.
  * On the classpath, in a file named `bootstrap.cfg`."
  [cli-args]
  (let [cli-data (parse-cli-args! cli-args)]
    (if-let [bootstrap-config (or (bootstrap/config-from-cli! cli-data)
                                (bootstrap/config-from-cwd)
                                (bootstrap/config-from-classpath))]
      (bootstrap* bootstrap-config cli-data)
      (throw (IllegalStateException.
               "Unable to find bootstrap.cfg file via --bootstrap-config command line argument, current working directory, or on classpath")))))

(defn get-service-fn
  "Given a trapperkeeper application, a service name, and a sequence of keys,
  returns the function provided by the service at that path."
  [^TrapperKeeperApp app service k & ks]
  {:pre [(keyword? service)
         (keyword? k)
         (every? keyword? ks)]
   :post [(ifn? %)]}
  (get-in (:graph-instance app) (cons service (cons k ks))))

