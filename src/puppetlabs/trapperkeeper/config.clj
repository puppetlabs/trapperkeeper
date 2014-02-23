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
  (:import  (java.io FileNotFoundException))
  (:require [clojure.java.io :refer [file]]
            [fs.core :as fs]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.config.typesafe :as typesafe]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.logging :refer [configure-logging!]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Service protocols

(defprotocol ConfigService
  (get-config [this] "Returns a map containing all of the configuration values")
  (get-in-config [this ks] [this ks default]
                 "Returns the individual configuration value from the nested
                 configuration structure, where ks is a sequence of keys.
                 Returns nil if the key is not present, or the default value if
                 supplied."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public
;; TODO: consolidate public sections

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defn config-file->map
  [file]
  (if (= (fs/extension file) ".ini")
    (ks/ini-to-map file)
    (typesafe/config-file->map file)))

(defn parse-config-path
  ([path]
   (parse-config-path path ["*.ini" "*.conf" "*.json" "*.properties"]))
  ([path glob-patterns]
   (when-not (.canRead (file path))
     (throw (FileNotFoundException.
              (format "Configuration path '%s' must exist and must be readable."
                      path))))
   (let [files (if-not (fs/directory? path)
                 [path]
                 (reduce
                   (fn [acc glob-pattern]
                     (concat acc (fs/glob (fs/file path glob-pattern))))
                   []
                   glob-patterns))]
     (->> files
          (map fs/absolute-path)
          (map config-file->map)
          (apply ks/deep-merge-with-keys
                 (fn [ks & _]
                   (throw (IllegalArgumentException.
                            (str "Duplicate configuration entry: " ks)))))
          (merge {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn parse-config-data
  "Parses the .ini configuration file(s) and returns a map of configuration data."
  [cli-data]
  {:post [(map? %)]}
  (let [debug? (or (:debug cli-data) false)]
    (-> (:config cli-data)
        (parse-config-path)
        (assoc :debug debug?))))

(defn initialize-logging!
  "Initializes the logging system based on the configuration data."
  [config-data]
  (let [debug?        (get-in config-data [:debug])
        log-config    (get-in config-data [:global :logging-config])]
    (configure-logging! log-config debug?)))
