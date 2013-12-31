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
            [puppetlabs.kitchensink.core :refer [inis-to-map]]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.logging :refer [configure-logging!]]))

(defn config-service
  "Returns trapperkeeper's configuration service.  Expects
   to find a command-line argument value for `:config`; the value of this
   parameter should be the path to an .ini file or a directory of .ini files."
  [config]
  ((service :config-service
    {:depends  []
     :provides [get-in-config get-config]}
    {:get-in-config (partial get-in config)
     :get-config    (fn [] config)})))

(defn- parse-config-file
  [config-file-path]
  {:pre  [(string? config-file-path)]
   :post [(map? %)]}
  (when-not (.canRead (file config-file-path))
    (throw (FileNotFoundException.
             (format "Configuration path '%s' must exist and must be readable."
                     config-file-path))))
  (inis-to-map config-file-path))

(defn parse-config-data
  "Parses the .ini configuration file(s) and returns a map of configuration data."
  [cli-data]
  {:post [(map? %)]}
  (let [debug? (or (:debug cli-data) false)]
    (-> (:config cli-data)
        (parse-config-file)
        (assoc :debug debug?))))

(defn initialize-logging!
  "Initializes the logging system based on the configuration data."
  [config-data]
  (let [debug?        (get-in config-data [:debug])
        log-config    (get-in config-data [:global :logging-config])]
    (configure-logging! log-config debug?)))
