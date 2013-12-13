(ns puppetlabs.trapperkeeper.bootstrap
  (:import (java.io Reader FileNotFoundException))
  (:require [clojure.java.io :refer [IOFactory]]
            [clojure.string :refer [trim]]
            [clojure.java.io :refer [reader resource file]]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.app :refer [validate-service-graph! service-graph?]]))

(def bootstrap-config-file-name "bootstrap.cfg")

(defn parse-bootstrap-line!
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line is expected to be of the form: '<namespace>/<service-fn-name>'.  Returns
  a 2-item vector containing the namespace and the service function name.  Throws
  an IllegalArgumentException if the line is not valid."
  [line]
  {:pre [(string? line)]
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

(defn call-service-fn!
  "Given the namespace and name of a service function, loads the namespace,
  calls the function, validates that the result is a valid service graph, and
  returns the graph.  Throws an `IllegalArgumentException` if the service graph
  cannot be loaded."
  [fn-ns fn-name]
  {:pre [(string? fn-ns)
         (string? fn-name)]
   :post [(service-graph? %)]}
  (try (require (symbol fn-ns))
       (catch FileNotFoundException e
         (throw (IllegalArgumentException.
                  (str "Unable to load service: "
                       fn-ns "/" fn-name)
                  e))))
  (if-let [service-fn (ns-resolve (symbol fn-ns)
                                  (symbol fn-name))]
    (validate-service-graph! (service-fn))
    (throw (IllegalArgumentException.
             (str "Unable to load service: "
                  fn-ns "/" fn-name)))))

(defn parse-bootstrap-config!
  "Parse the trapperkeeper bootstrap configuration and return the service graph
  that is the result of merging the graphs of all of the services specified in
  the configuration."
  [config]
  {:pre [(satisfies? IOFactory config)]
   :post [(sequential? %)
          (every? service-graph? %)]}
  (let [lines (line-seq (reader config))]
    (when (empty? lines) (throw (Exception. "Empty bootstrap config file")))
    (for [line (map trim lines)
          :when (not (empty? line))]
      (let [[service-fn-namespace service-fn-name] (parse-bootstrap-line! line)]
        (call-service-fn! service-fn-namespace service-fn-name)))))

(defn config-from-cli!
  "Given the data from the command-line (parsed via `core/parse-cli-args!`),
  check to see if the caller explicitly specified the location of the bootstrap
  config file.  If so, return it.  Throws an IllegalArgumentException if a
  location was specified but the file doesn't actually exist."
  [cli-data]
  {:pre [(map? cli-data)]
   :post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (when (contains? cli-data :bootstrap-config)
    (when-let [config-path (cli-data :bootstrap-config)]
      (if (.exists (file config-path))
        (do
          (log/debug (str "Loading bootstrap config from specified path: '" config-path "'"))
          config-path)
        (throw (IllegalArgumentException.
                 (str "Specified bootstrap config file does not exist: '" config-path "'")))))))

(defn config-from-cwd
  "Check to see if there is a bootstrap config file in the current working
  directory;  if so, return it."
  []
  {:post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (let [config-file (-> bootstrap-config-file-name
                        file
                        .getAbsoluteFile)]
    (when (.exists config-file)
      (log/debug (str "Loading bootstrap config from current working directory: '"
                      (.getAbsolutePath config-file) "'"))
      config-file)))

(defn config-from-classpath
  "Check to see if there is a bootstrap config file available on the classpath;
  if so, return it."
  []
  {:post [(or (nil? %)
              (satisfies? IOFactory %))]}
  (when-let [classpath-config (resource bootstrap-config-file-name)]
    (log/debug (str "Loading bootstrap config from classpath: '" classpath-config "'"))
    classpath-config))
