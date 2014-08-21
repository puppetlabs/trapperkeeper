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
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.logging :refer [configure-logging!]]))

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
    (edn/read (PushbackReader. (io/reader file)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn load-config
  [path]
  (when-not (.canRead (io/file path))
    (throw (FileNotFoundException.
             (format "Configuration path '%s' must exist and must be readable."
                     path))))
  (let [files (if-not (fs/directory? path)
                [path]
                (mapcat
                  #(fs/glob (fs/file path %))
                  ["*.ini" "*.conf" "*.json" "*.properties" "*.edn"]))]
    (->> files
         (map fs/absolute-path)
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
  [config]
  (service ConfigService
           []
           (get-config [this] config)
           (get-in-config [this ks] (get-in config ks))
           (get-in-config [this ks default] (get-in config ks default))))

(defn parse-config-data
  "Parses the .ini, .edn, .conf, .json, or .properties configuration file(s)
   and returns a map of configuration data. If no configuration file is
   explicitly specified, will act as if it was given an empty configuration
   file."
  [cli-data]
  {:post [(map? %)]}
  (let [debug? (or (:debug cli-data) false)]
    (if-not (contains? cli-data :config)
      {:debug debug?}
      (-> (:config cli-data)
         (load-config)
         (assoc :debug debug?)))))

(defn initialize-logging!
  "Initializes the logging system based on the configuration data."
  [config-data]
  (let [debug?        (get-in config-data [:debug])
        log-config    (get-in config-data [:global :logging-config])]
    (configure-logging! log-config debug?)))
