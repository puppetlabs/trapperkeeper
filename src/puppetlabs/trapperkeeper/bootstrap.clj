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
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [try+ throw+]]))

;; Schemas
(def ParsedBootstrapEntry {:namespace schema/Str :service-name schema/Str})
(def BootstrapFiles [(schema/protocol io/IOFactory)])
(def AnnotatedBootstrapEntry {:entry schema/Str
                    :bootstrap-file schema/Str
                    :line-number schema/Int})

;; Constants
(def bootstrap-config-file-name "bootstrap.cfg")

(schema/defn parse-bootstrap-line! :- ParsedBootstrapEntry
  "Parses an individual line from a trapperkeeper bootstrap configuration file.
  Each line is expected to be of the form: '<namespace>/<service-name>'.  Returns
  a 2-item vector containing the namespace and the service name.  Throws
  an IllegalArgumentException if the line is not valid."
  [line :- schema/Str]
  (if-let [[match namespace service-name] (re-matches
                                           #"^([a-zA-Z0-9\.\-]+)/([a-zA-Z0-9\.\-]+)$"
                                           line)]
    {:namespace namespace :service-name service-name}
    (throw+ {:type :illegal-argument
             :message (str "Invalid line in bootstrap config file:\n\n\t"
                           line
                           "\n\nAll lines must be of the form: '<namespace>/<service-fn-name>'.")})))

(schema/defn ^:private resolve-service! :- (schema/protocol services/ServiceDefinition)
  "Given the namespace and name of a service, loads the namespace,
  calls the function, validates that the result is a valid service definition, and
  returns the service definition.  Throws an `IllegalArgumentException` if the
  service definition cannot be resolved."
  [resolve-ns :- schema/Str
   service-name :- schema/Str]
  (try (require (symbol resolve-ns))
       (catch FileNotFoundException e
         (throw+ {:type :illegal-argument
                  :message (str "Unable to load service: " resolve-ns "/" service-name)
                  :cause e})))
  (if-let [service-def (ns-resolve (symbol resolve-ns) (symbol service-name))]
    (internal/validate-service-graph! (var-get service-def))
    (throw+ {:type :illegal-argument
             :message (str "Unable to load service: " resolve-ns "/" service-name)})))

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
              ;; If we can't find a file or a directory, and loading the URI
              ;; fails, we give up and throw an exception.
              ;; Don't wrap and re-throw here, as `ignored` may be misleading
              ;; (in the case of a normal path, it is useless - there is no
              ;; reason to mess with URIs)
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

(schema/defn indexed
  "Returns seq of [index, item] pairs
  [:a :b :c] -> ([0 :a] [1 :b] [2 :c])"
  [coll]
  (map vector (iterate inc 0) coll))

(schema/defn get-annotated-bootstrap-entries :- [AnnotatedBootstrapEntry]
  [configs :- [(schema/protocol io/IOFactory)]]
  (flatten (for [config configs]
             (for [[line-number line-text] (indexed (map remove-comments (line-seq (io/reader config))))
                   :when (not (empty? line-text))]
               {:bootstrap-file (str config)
                :line-number (inc line-number)
                :entry line-text}))))

(defn find-duplicates
  "Collects duplicates base on running f on each item.
   Returns a map where the keys will be the result of running f on each item,
   and the values will be lists of items that are duplicates of eachother"
  [coll f]
  (->> coll
       (group-by f)
       ; filter out map values with only 1 item
       (remove #(= 1 (count (val %))))))

(schema/defn duplicate-protocol-error
  "Returns an IllegalArgumentException with a nice error message
   saying in what file and line each service entry was found"
  [protocol-id :- schema/Keyword
   duplicate-services :- [(schema/protocol services/ServiceDefinition)]
   service->entry-map :- {(schema/protocol services/ServiceDefinition) AnnotatedBootstrapEntry}]
  (let [make-error-message (fn [service]
                             (let [entry (get service->entry-map service)]
                               (format "%s:%s\n%s"
                                       (:bootstrap-file entry)
                                       (:line-number entry)
                                       (:entry entry))))]
    (IllegalArgumentException.
      (format (str "Duplicate implementations found for service protocol '%s':\n%s")
              protocol-id
              (string/join "\n" (map make-error-message duplicate-services))))))

(schema/defn check-duplicate-service-implementations
  "Throws an exception if two services implement the same service protocol"
  [services :- [(schema/protocol services/ServiceDefinition)]
   bootstrap-entries :- [AnnotatedBootstrapEntry]]

  ; Zip up the services and bootstrap entries and construct a map out of them
  ; to use as a lookup table below
  (let [service->entry-map (zipmap services bootstrap-entries)]
    ; Find duplicates base on the service id returned by calling service-def-id
    ; on each service
    (if-let [duplicate (first (find-duplicates services services/service-def-id))]
      (throw (duplicate-protocol-error (key duplicate) (val duplicate) service->entry-map)))))

(schema/defn bootstrap-error :- IllegalArgumentException
  [entry :- schema/Str
   bootstrap-file :- schema/Str
   line-number :- schema/Int
   original-message :- schema/Str]
  (IllegalArgumentException.
    (format (str "Problem loading service '%s' on line '%s' in bootstrap "
                 "configuration file '%s':\n%s")
            entry line-number bootstrap-file original-message)))

(schema/defn resolve-services!
  [bootstrap-entries]
  (for [{:keys [bootstrap-file line-number entry]} bootstrap-entries]
    (try+
      (let [{:keys [namespace service-name]} (parse-bootstrap-line! entry)]
        (resolve-service! namespace service-name ))
      ; Catch and re-throw as java exception
      (catch [:type :illegal-argument] {:keys [message]}
        (throw (bootstrap-error entry bootstrap-file line-number message))))))

(schema/defn remove-duplicate-entries :- [AnnotatedBootstrapEntry]
  "Removes duplicate entries by only looking at the :entry key in each entry map.
   This way, duplicate entries in different files and on different lines are removed"
  [entries :- [AnnotatedBootstrapEntry]]
  ; Construct a [k v] pair where the key is the text of the bootstrap entry, and the
  ; value is the original AnnotatedBootstrapEntry map. By shoving them into a map,
  ; duplicate keys are ignored. We pick out the values with vals, and get back our
  ; original list minus the duplicates.
  (vals (into {} (for [entry entries] [(:entry entry) entry]))))

(schema/defn parse-bootstrap-configs! :- [(schema/protocol services/ServiceDefinition)]
  [configs :- [(schema/protocol io/IOFactory)]]
  ; We remove the duplicate entries to allow the user to have duplicate entries in their
  ; bootstrap files. If we didn't remove them, it would look like two services were trying
  ; to implement the same protocol when we check for duplicate service implementations
  (let [bootstrap-entries (remove-duplicate-entries (get-annotated-bootstrap-entries configs))]
    (when (empty? bootstrap-entries)
      (throw (Exception. "Empty bootstrap config file")))
    (let [resolved-services (resolve-services! bootstrap-entries)
          _ (check-duplicate-service-implementations resolved-services bootstrap-entries)]
      resolved-services)))

(schema/defn parse-bootstrap-config! :- [(schema/protocol services/ServiceDefinition)]
  "Parse a single bootstrap configuration file and return the service graph
  that is the result of merging the graphs of all the services specified in the
  configuration file"
  [config :- (schema/protocol io/IOFactory)]
  (parse-bootstrap-configs! [config]))
