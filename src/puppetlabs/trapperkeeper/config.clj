;;;;
;;;; This namespace contains trapperkeeper's built-in configuration service,
;;;; which is based on .ini config files.
;;;;
;;;; This service provides a function, `get-in-config`, which can be used to
;;;; retrieve the config data read from the ini files.  For example,
;;;; given an .ini file with the following contents:
;;;;
;;;;   [foo]
;;;;   bar = baz
;;;;
;;;; The value of `(get-in-config [:foo :bar])` would be `"baz"`.
;;;;
;;;; Also provides a second function, `get-config`, which simply returns
;;;; the entire map of configuration data.
;;;;

(ns puppetlabs.trapperkeeper.config
  (:import  (java.io FileNotFoundException PushbackReader))
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [me.raynes.fs :as fs]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.config.typesafe :as typesafe]
            [clj-yaml.core :as yaml]
            [puppetlabs.trapperkeeper.services :refer [service service-context]]
            [puppetlabs.trapperkeeper.logging :refer [configure-logging!]]
            [clojure.tools.logging :as log]
            [schema.core :as schema]
            [puppetlabs.trapperkeeper.common :as common]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Service protocol

(defprotocol ConfigService
  (get-config [this] "Returns a map containing all of the configuration values")
  (get-in-config [this ks] [this ks default]
    "Returns the individual configuration value from the nested
                 configuration structure, where ks is a sequence of keys.
                 Returns nil if the key is not present, or the default value if
                 supplied."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn config-file->map
  [file]
  (condp (fn [vals ext] (contains? vals ext)) (fs/extension file)
    #{".ini"}
    (ks/ini-to-map file)

    #{".json" ".conf" ".properties"}
    (typesafe/config-file->map file)

    #{".edn"}
    (edn/read (PushbackReader. (io/reader file)))

    #{".yaml" ".yml"}
    (yaml/parse-string (slurp file))))

(defn override-restart-file-from-cli-data
  [config-data cli-data]
  (if-let [cli-restart-file (:restart-file cli-data)]
    (assoc-in config-data [:global :restart-file] cli-restart-file)
    config-data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn get-files-from-config
  "Given a path to a file or directory, return a list of all files
   contained that have valid extensions for a TK config."
  [path]
  (when-not (.canRead (io/file path))
    (throw (FileNotFoundException.
            (format "Configuration path '%s' must exist and must be readable."
                    path))))
  (if-not (fs/directory? path)
    [path]
    (mapcat
     #(fs/glob (fs/file path %))
     ["*.ini" "*.conf" "*.json" "*.properties" "*.edn" "*.yaml" "*.yml"])))

(defn load-config
  "Given a path to a configuration file or directory of configuration files,
   or a string of multiple paths separated by comma, parse the config files and build
   up a trapperkeeper config map.  Can be used to implement CLI tools that need
   access to trapperkeeper config data but don't need to boot the full TK framework."
  [paths]
  (let [files (flatten (map get-files-from-config (str/split paths #",")))]
    (->> files
         (map ks/absolute-path)
         (map config-file->map)
         (apply ks/deep-merge-with-keys
                (fn [ks & _]
                  (throw (IllegalArgumentException.
                          (str "Duplicate configuration entry: " ks)))))
         (merge {}))))

(defn config-service
  "Returns trapperkeeper's configuration service.  Expects
   to find a command-line argument value for `:config`; the value of this
   parameter should be the path to an .ini file or a directory of .ini files."
  [config-data-fn]
  (service ConfigService
    []
    (init [this context]
      (assoc context :config (config-data-fn)))
    (get-config [this]
      (let [{:keys [config]} (service-context this)]
        config))
    (get-in-config [this ks]
      (let [{:keys [config]} (service-context this)]
        (get-in config ks)))
    (get-in-config [this ks default]
      (let [{:keys [config]} (service-context this)]
        (get-in config ks default)))))

(schema/defn parse-config-data :- (schema/pred map?)
  "Parses the .ini, .edn, .conf, .json, or .properties configuration file(s)
   and returns a map of configuration data. If no configuration file is
   explicitly specified, will act as if it was given an empty configuration
   file."
  [cli-data :- common/CLIData]
  (let [debug? (or (:debug cli-data) false)]
    (if-not (contains? cli-data :config)
      {:debug debug?}
      (-> (:config cli-data)
          (load-config)
          (assoc :debug debug?)
          (override-restart-file-from-cli-data cli-data)))))

(defn initialize-logging!
  "Initializes the logging system based on the configuration data."
  [config-data]
  (let [debug?        (get-in config-data [:debug])
        log-config    (get-in config-data [:global :logging-config])]
    (configure-logging! log-config debug?)))
