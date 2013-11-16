(ns puppetlabs.trapperkeeper.services.config.config-service
  (:import (java.io FileNotFoundException))
  (:require [clojure.java.io :refer [file]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [puppetlabs.kitchensink.core :refer [inis-to-map]]))

(defservice config-service
   "A simple configuration service based on .ini config files.  Expects
   to find a command-line argument value for `:config` (which it will
   retrieve from the `:cli-service`'s `cli-data` fn); the value of this
   parameter should be the path to an .ini file or a directory of .ini
   files.

   Provides a single function, `get-in-config`, which can be used to
   retrieve the config data read from the ini files.  For example,
   given an ini file with the following contents:

       [foo]
       bar = baz

   The value of `(get-in-config [:foo :bar])` would be `\"baz\"`."
   {:depends [[:cli-service cli-data]]
    :provides [get-in-config get-config]}
   (when-not (cli-data :config)
     (throw (IllegalStateException.
              "Command line argument --config (or -c) is required by the config service")))
   (let [config-path (cli-data :config)]
     (when-not (.canRead (file config-path))
       (throw (FileNotFoundException.
                (format
                  "Configuration path '%s' must exist and must be readable."
                  config-path))))
     (let [config (inis-to-map config-path)]
       {:get-in-config (fn [ks] (get-in config ks))
        :get-config (fn [] config)})))
