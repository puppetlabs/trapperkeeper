(ns puppetlabs.trapperkeeper.core
  (:require [plumbing.graph :as graph]
            [clojure.java.io :refer [file]]
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :refer [inis-to-map cli!]]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.logging :refer [configure-logging!]]
            [puppetlabs.trapperkeeper.app :refer [service-graph?]]
            [puppetlabs.trapperkeeper.shutdown :refer [register-shutdown-hooks! wait-for-shutdown
                                                       shutdown! initiated-internally? call-error-handler!]])
  (:import (java.io FileNotFoundException)
           (puppetlabs.trapperkeeper.app TrapperKeeperApp)))

(def #^{:macro true
        :doc "An alias for the `puppetlabs.trapperkeeper.services/service` macro
             so that it is accessible from the core namespace along with the
             rest of the API."}
  service #'services/service)

(def #^{:macro true
        :doc "An alias for the `puppetlabs.trapperkeeper.services/defservice` macro
             so that it is accessible from the core namespace along with the
             rest of the API."}
  defservice #'services/defservice)

(defn config-service
  "A simple configuration service based on .ini config files.  Expects
   to find a command-line argument value for `:config`; the value of this
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
  ((services/service :config-service
    {:depends  []
     :provides [get-in-config get-config]}
    {:get-in-config (fn [ks] (get-in config ks))
     :get-config    (fn [] config)})))

(defn- parse-config-file
  [config-file-path]
  {:pre  [(not (nil? config-file-path))]
   :post [(map? %)]}
  (when-not (.canRead (file config-file-path))
    (throw (FileNotFoundException.
             (format
               "Configuration path '%s' must exist and must be readable."
               config-file-path))))
  (inis-to-map config-file-path))

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
  {:pre  [(sequential? services)
          (every? service-graph? services)
          (map? cli-data)]
   :post [(instance? TrapperKeeperApp %)]}
  (let [debug           (or (cli-data :debug) false)
        config-data     (-> (parse-config-file (cli-data :config))
                            (assoc :debug debug))
        config-service  (config-service config-data)
        _               (configure-logging! (get-in config-data [:global :logging-config]) (cli-data :debug))
        graph-map       (-> (apply merge config-service services)
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

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
    Hard-codes the command-line arguments expected by trapperkeeper to be:
        --debug
        --bootstrap-config <bootstrap file>
        --config <.ini file or directory>"
  [cli-args]
  (let [specs       [["-d" "--debug" "Turns on debug mode" :flag true]
                     ["-b" "--bootstrap-config" "Path to bootstrap config file"]
                     ["-c" "--config" "Path to .ini file or directory of .ini files to be read and consumed by services"]]
        required    [:config]]
    (first (cli! cli-args specs required))))

(defn run-app
  "Given a bootstrapped TrapperKeeper app, let the application run until shut down,
  which may be triggered by one of several different ways. In all cases, services
  will be shut down and any exceptions they might throw will be caught and logged."
  [^TrapperKeeperApp app]
  (let [shutdown-reason (wait-for-shutdown app)]
    (when (initiated-internally? shutdown-reason)
      (call-error-handler! shutdown-reason)
      (shutdown!)
      (when-let [error (:error shutdown-reason)]
        (throw error)))))

(defn run
  "Bootstraps a trapperkeeper application and runs it.
  Blocks the calling thread until trapperkeeper is shut down.
  `cli-data` is expected to be a map constructed by parsing the CLI args.
  (see `parse-cli-args`)"
  [cli-data]
  {:pre [(map? cli-data)]}
  (-> cli-data
      (bootstrap)
      (run-app)))

(defn main
  [& args]
  (try+
    (-> args
        (parse-cli-args!)
        (run))
    (catch map? {:keys [error-message]}
      (println error-message))))
