(ns puppetlabs.trapperkeeper.bootstrap
  (:import (java.io Reader FileNotFoundException)
           (java.net URI))
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.plugins :as plugins]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.services :as services]))

(def bootstrap-config-file-name "bootstrap.cfg")

(defn- parse-bootstrap-line!
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line is expected to be of the form: '<namespace>/<service-name>'.  Returns
  a 2-item vector containing the namespace and the service name.  Throws
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

(defn- resolve-service!
  "Given the namespace and name of a service, loads the namespace,
  calls the function, validates that the result is a valid service definition, and
  returns the service definition.  Throws an `IllegalArgumentException` if the
  service definition cannot be resolved."
  [resolve-ns service-name]
  {:pre  [(string? resolve-ns)
          (string? service-name)]
   :post [(satisfies? services/ServiceDefinition %)]}
  (try (require (symbol resolve-ns))
       (catch FileNotFoundException e
         (throw (IllegalArgumentException.
                  (str "Unable to load service: " resolve-ns "/" service-name)
                  e))))
  (if-let [service-def (ns-resolve (symbol resolve-ns) (symbol service-name))]
    (internal/validate-service-graph! (var-get service-def))
    (throw (IllegalArgumentException.
             (str "Unable to load service: " resolve-ns "/" service-name)))))

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
  (let [config-file (io/file config-path)]
    (if (.exists config-file)
      config-file

      ;; if that didn't work (won't for a URI of a file inside a .jar),
      ;; get a bigger hammer
      (try
        (io/input-stream (URI. config-path))
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
              (satisfies? io/IOFactory %))]}
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
              (satisfies? io/IOFactory %))]}
  (let [config-file (-> bootstrap-config-file-name
                        (io/file)
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
              (satisfies? io/IOFactory %))]}
  (when-let [classpath-config (io/resource bootstrap-config-file-name)]
    (log/debug (str "Loading bootstrap config from classpath: '" classpath-config "'"))
    classpath-config))

(defn find-bootstrap-config
  "Get the bootstrap config file from:
    1. the file path specified on the command line, or
    2. the current working directory, or
    3. the classpath
  Throws an exception if the file cannot be found."
  [cli-data]
  {:pre  [(map? cli-data)]
   :post [(or (nil? %)
              (satisfies? io/IOFactory %))]}
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
  {:pre  [(satisfies? io/IOFactory config)]
   :post [(sequential? %)
          (every? #(satisfies? services/ServiceDefinition %) %)]}
  (let [lines (line-seq (io/reader config))]
    (when (empty? lines) (throw (Exception. "Empty bootstrap config file")))
    (for [line (map remove-comments lines)
          :when (not (empty? line))]
      (let [[service-namespace service-name] (parse-bootstrap-line! line)]
        (resolve-service! service-namespace service-name)))))

