(ns puppetlabs.trapperkeeper.config
  (:import  (java.io FileNotFoundException))
  (:require [clojure.java.io :refer [file]]
            [puppetlabs.kitchensink.core :refer [inis-to-map]]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.logging :refer [configure-logging!]]))

(defn- config-service
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
  ((service :config-service
    {:depends  []
     :provides [get-in-config get-config]}
    {:get-in-config (partial get-in config)
     :get-config    (fn [] config)})))

(defn- parse-config-file
  [config-file-path]
  {:pre  [(not (nil? config-file-path))]
   :post [(map? %)]}
  (when-not (.canRead (file config-file-path))
    (throw (FileNotFoundException.
             (format "Configuration path '%s' must exist and must be readable." config-file-path))))
  (inis-to-map config-file-path))

(defn configure!
  [cli-data services]
  (let [debug?      (or (:debug cli-data) false)
        config-data (-> (:config cli-data)
                        (parse-config-file)
                        (assoc :debug debug?))
        log-config  (get-in config-data [:global :logging-config])]
    (configure-logging! log-config debug?)
    (apply merge (config-service config-data) services)))
