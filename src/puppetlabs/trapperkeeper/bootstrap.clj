(ns puppetlabs.trapperkeeper.bootstrap
  (:import (java.io FileNotFoundException)
           (java.net URI))
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.trapperkeeper.common :as common]
            [schema.core :as schema]
            [me.raynes.fs :as fs]))

;; Schemas
(def BootstrapLine {:namespace schema/Str :service-name schema/Str})
(def BootstrapFiles [(schema/protocol io/IOFactory)])

;; Constants
(def bootstrap-config-file-name "bootstrap.cfg")

(schema/defn ^:private parse-bootstrap-line! :- BootstrapLine
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line is expected to be of the form: '<namespace>/<service-name>'.  Returns
  a 2-item vector containing the namespace and the service name.  Throws
  an IllegalArgumentException if the line is not valid."
  [line :- schema/Str]
  (if-let [[match namespace service-name] (re-matches
                                            #"^([a-zA-Z0-9\.\-]+)/([a-zA-Z0-9\.\-]+)$"
                                            line)]
    {:namespace namespace :service-name service-name}
    (throw (IllegalArgumentException.
             (str "Invalid line in bootstrap config file:\n\n\t"
                  line
                  "\n\nAll lines must be of the form: '<namespace>/<service-fn-name>'.")))))

(schema/defn ^:private resolve-service! :- (schema/protocol services/ServiceDefinition)
  "Given the namespace and name of a service, loads the namespace,
  calls the function, validates that the result is a valid service definition, and
  returns the service definition.  Throws an `IllegalArgumentException` if the
  service definition cannot be resolved."
  [resolve-ns :- schema/Str
   service-name :- schema/Str]
  (try (require (symbol resolve-ns))
       (catch FileNotFoundException e
         (throw (IllegalArgumentException.
                  (str "Unable to load service: " resolve-ns "/" service-name)
                  e))))
  (if-let [service-def (ns-resolve (symbol resolve-ns) (symbol service-name))]
    (internal/validate-service-graph! (var-get service-def))
    (throw (IllegalArgumentException.
             (str "Unable to load service: " resolve-ns "/" service-name)))))

(schema/defn ^:private remove-comments :- schema/Str
  "Given a line of text from the bootstrap config file, remove
  anything that is commented out with either a '#' or ';'. If
  the entire line is commented out, an empty string is returned."
  [line :- schema/Str]
  (-> line
      (string/replace #"(?:#|;).*$" "")
      (string/trim)))

(schema/defn find-bootstraps-from-path :- BootstrapFiles
  "Given a path, return a list of .cfg files found there.
   - If the path leads directly to a file, return a list with a single item.
   - If the path leads to a directory, return a list of any .cfg files found there.
   - If the path doesn't lead to a file or directory, attempt to load a file from
   a URI (for files in jars)"
  [config-path :- schema/Str]
  (cond
    (fs/file? config-path) [(fs/file config-path)]
    (fs/directory? config-path) (fs/glob (fs/file config-path "*.cfg"))
    :else (try
            [(io/input-stream (URI. config-path))]
            (catch Exception ignored
              ;; If that didn't work either, we can't read it.  Don't wrap and
              ;; re-throw here, as `ignored` may be misleading (in the case of a
              ;; normal path, it is useless - there is no reason to mess with URIs)
              (throw (IllegalArgumentException.
                       (str "Specified bootstrap config file does not exist: '"
                            config-path "'")))))))

(schema/defn ^:private config-from-cli :- (schema/maybe BootstrapFiles)
  "Given the data from the command-line (parsed via `core/parse-cli-args!`),
  check to see if the caller explicitly specified the location of one or more
  bootstrap config files.  If so, return an object that can be read via
  `reader` (will normally be a `file`, but in the case of a config file inside
  of a .jar, it will be an `input-stream`).  Throws an IllegalArgumentException
  if a location was specified but the file doesn't actually exist."
  [cli-data :- common/CLIData]
  (when (contains? cli-data :bootstrap-config)
    (when-let [config-path (cli-data :bootstrap-config)]
      (let [config-files (flatten (map
                                    find-bootstraps-from-path
                                    (string/split config-path #",")))]
        (log/debug (format "Loading bootstrap configs:\n%s"
                        (string/join "\n" config-files)))
        config-files))))

(schema/defn ^:private config-from-cwd :- (schema/maybe BootstrapFiles)
  "Check to see if there is a bootstrap config file in the current working
  directory;  if so, return it."
  []
  (let [config-file (-> bootstrap-config-file-name
                        (io/file)
                        (.getAbsoluteFile))]
    (when (.exists config-file)
      (log/debug (str "Loading bootstrap config from current working directory: '"
                      (.getAbsolutePath config-file) "'"))
      [config-file])))

(schema/defn ^:private config-from-classpath :- (schema/maybe BootstrapFiles)
  "Check to see if there is a bootstrap config file available on the classpath;
  if so, return it."
  []
  (when-let [classpath-config (io/resource bootstrap-config-file-name)]
    (log/debug (str "Loading bootstrap config from classpath: '" classpath-config "'"))
    [classpath-config]))

(schema/defn find-bootstrap-configs :- BootstrapFiles
  "Get the bootstrap config files from:
    1. the file path specified on the command line, or
    2. the current working directory, or
    3. the classpath
  Throws an exception if the file cannot be found."
  [cli-data :- common/CLIData]
  (if-let [bootstrap-configs (or (config-from-cli cli-data)
                                 (config-from-cwd)
                                 (config-from-classpath))]
    bootstrap-configs
    (throw (IllegalStateException.
             (str "Unable to find bootstrap.cfg file via --bootstrap-config "
                  "command line argument, current working directory, or on classpath")))))

(schema/defn chain-files :- [schema/Str]
  "Takes a list of files, reads all their lines in, and returns a flattened seq
   of all their lines put together.

   In the case of an empty file, nil would be returned by line-seq, so we remove
   all the nils at the end"
  [files :- [(schema/protocol io/IOFactory)]]
  (remove nil? (flatten (map #(line-seq (io/reader %)) files))))

(schema/defn parse-bootstrap-configs! :- [(schema/protocol services/ServiceDefinition)]
  "Parse the trapperkeeper bootstrap configuration and return the service graph
  that is the result of merging the graphs of all of the services specified in
  the configuration."
  [configs :- [(schema/protocol io/IOFactory)]]
  (let [lines (chain-files configs)]
    (when (empty? lines) (throw (Exception. "Empty bootstrap config file")))
    (for [line (map remove-comments lines)
          :when (not (empty? line))]
      (let [{:keys [namespace service-name]} (parse-bootstrap-line! line)]
        (resolve-service! namespace service-name)))))

