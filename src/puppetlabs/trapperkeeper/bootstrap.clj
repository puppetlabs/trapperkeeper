(ns puppetlabs.trapperkeeper.bootstrap
  (:import (java.io Reader FileNotFoundException)
           (java.net URI))
  (:require [clojure.java.io :refer [IOFactory]]
            [clojure.string :as string]
            [clojure.java.io :refer [reader resource file input-stream]]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.plugins :as plugins]
            [puppetlabs.trapperkeeper.config :refer [parse-config-data
                                                     initialize-logging!
                                                     config-service]]
            [puppetlabs.trapperkeeper.app :refer [validate-service-graph!
                                                  service-graph?
                                                  compile-graph
                                                  instantiate
                                                  register-shutdown-hooks!]])
  (:import (puppetlabs.trapperkeeper.app TrapperKeeperApp)))

(def bootstrap-config-file-name "bootstrap.cfg")

(defn- parse-bootstrap-line!
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line is expected to be of the form: '<namespace>/<service-fn-name>'.  Returns
  a 2-item vector containing the namespace and the service function name.  Throws
  an IllegalArgumentException if the line is not valid."
  [line]
  {:pre  [(string? line)]
   :post [(vector? %)
          (= 2 (count %))
          (every? string? %)]}
  (if-let [[match namespace fn-name] (re-matches
                                       #"^([a-zA-Z0-9\.\-]+)/([a-zA-Z0-9\.\-]+)$"
                                       line)]
    [namespace fn-name]
    (throw (IllegalArgumentException.
             (str "Invalid line in bootstrap config file:\n\n\t"
                  line
                  "\n\nAll lines must be of the form: '<namespace>/<service-fn-name>'.")))))

(defn- call-service-fn!
  "Given the namespace and name of a service function, loads the namespace,
  calls the function, validates that the result is a valid service graph, and
  returns the graph.  Throws an `IllegalArgumentException` if the service graph
  cannot be loaded."
  [fn-ns fn-name]
  {:pre  [(string? fn-ns)
          (string? fn-name)]
   :post [(service-graph? %)]}
  (try (require (symbol fn-ns))
       (catch FileNotFoundException e
         (throw (IllegalArgumentException.
                  (str "Unable to load service: " fn-ns "/" fn-name)
                  e))))
  (if-let [service-fn (ns-resolve (symbol fn-ns) (symbol fn-name))]
    (validate-service-graph! (service-fn))
    (throw (IllegalArgumentException.
             (str "Unable to load service: " fn-ns "/" fn-name)))))

(defn- remove-comments
  "Given a line of text from the bootstrap config file, remove
  anything that is commented out with either a '#' or ';'. If
  the entire line is commented out, an empty string is returned."
  [line]
  {:pre  [(string? line)]
   :post [(string? %)]}
  (-> line
      (string/replace #"(?:#|;).*$" "")
      (string/trim)))

(defn- get-config-source
  [config-path]
  (let [config-file (file config-path)]
    (if (.exists config-file)
      config-file

      ;; if that didn't work (won't for a URI of a file inside a .jar),
      ;; get a bigger hammer
      (try
        (input-stream (URI. config-path))
        (catch Exception ignored
          ;; If that didn't work either, we can't read it.  Don't wrap and
          ;; re-throw here, as `ignored` may be misleading (in the case of a
          ;; normal path, it is useless - there is no reason to mess with URIs)
          (throw (IllegalArgumentException.
                   (str "Specified bootstrap config file does not exist: '"
                        config-path "'"))))))))

(defn- config-from-cli
  "Given the data from the command-line (parsed via `core/parse-cli-args!`),
  check to see if the caller explicitly specified the location of the bootstrap
  config file.  If so, return an object that can be read via `reader` (will
  normally be a `file`, but in the case of a config file inside of a .jar, it
  will be an `input-stream`).  Throws an IllegalArgumentException if a
  location was specified but the file doesn't actually exist."
  [cli-data]
  {:pre  [(map? cli-data)]
   :post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (when (contains? cli-data :bootstrap-config)
    (when-let [config-path (cli-data :bootstrap-config)]
      (let [config-file (get-config-source config-path)]
        (log/debug (str "Loading bootstrap config from specified path: '"
                        config-path "'"))
        config-file))))


(defn- config-from-cwd
  "Check to see if there is a bootstrap config file in the current working
  directory;  if so, return it."
  []
  {:post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (let [config-file (-> bootstrap-config-file-name
                        (file)
                        (.getAbsoluteFile))]
    (when (.exists config-file)
      (log/debug (str "Loading bootstrap config from current working directory: '"
                      (.getAbsolutePath config-file) "'"))
      config-file)))

(defn- config-from-classpath
  "Check to see if there is a bootstrap config file available on the classpath;
  if so, return it."
  []
  {:post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (when-let [classpath-config (resource bootstrap-config-file-name)]
    (log/debug (str "Loading bootstrap config from classpath: '" classpath-config "'"))
    classpath-config))

(defn- find-bootstrap-config
  "Get the bootstrap config file from:
    1. the file path specified on the command line, or
    2. the current working directory, or
    3. the classpath
  Throws an exception if the file cannot be found."
  [cli-data]
  {:pre  [(map? cli-data)]
   :post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (if-let [bootstrap-config (or (config-from-cli cli-data)
                                (config-from-cwd)
                                (config-from-classpath))]
    bootstrap-config
    (throw (IllegalStateException.
             (str "Unable to find bootstrap.cfg file via --bootstrap-config "
                  "command line argument, current working directory, or on classpath")))))

(defn parse-bootstrap-config!
  "Parse the trapperkeeper bootstrap configuration and return the service graph
  that is the result of merging the graphs of all of the services specified in
  the configuration."
  [config]
  {:pre  [(satisfies? IOFactory config)]
   :post [(sequential? %)
          (every? service-graph? %)]}
  (let [lines (line-seq (reader config))]
    (when (empty? lines) (throw (Exception. "Empty bootstrap config file")))
    (for [line (map remove-comments lines)
          :when (not (empty? line))]
      (let [[service-fn-namespace service-fn-name] (parse-bootstrap-line! line)]
        (call-service-fn! service-fn-namespace service-fn-name)))))

(defn bootstrap-services
  "Given the services to run and command-line arguments,
   bootstrap and return the trapperkeeper application."
  [services config-data]
  {:pre  [(sequential? services)
          (every? service-graph? services)]
   :post [(instance? TrapperKeeperApp %)]}
  (-> (apply merge (config-service config-data) services)
      (register-shutdown-hooks!)
      (compile-graph)
      (instantiate)
      (TrapperKeeperApp.)))

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
  {:pre  [(map? cli-data)
          (contains? cli-data :config)]
   :post [(instance? TrapperKeeperApp %)]}
  ;; There is a strict order of operations that need to happen here:
  ;; 1. parse config files
  ;; 2. initialize logging
  ;; 3. initialize plugin system
  ;; 4. bootstrap rest of framework
  (let [config-data (parse-config-data cli-data)]
    (initialize-logging! config-data)
    (plugins/add-plugin-jars-to-classpath! (cli-data :plugins))
    (-> cli-data
        (find-bootstrap-config)
        (parse-bootstrap-config!)
        (bootstrap-services config-data))))
