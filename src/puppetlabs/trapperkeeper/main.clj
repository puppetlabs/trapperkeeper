(ns puppetlabs.trapperkeeper.main
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]))

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
    Hard-codes the command-line arguments expected by trapperkeeper to be:
        --debug
        --bootstrap-config <bootstrap file>
        --config <.ini file or directory>"
  [cli-args]
  (let [specs       [["-d" "--debug" "Turns on debug mode" :flag true]
                     ["-b" "--bootstrap-config" "Path to bootstrap config file"]
                     ["-c" "--config" "Path to .ini file or directory of .ini files to be read and consumed by services"]]
        required    [:config]]
    (first (kitchensink/cli! cli-args specs required))))

(defn main
  [& args]
  (-> args
      (parse-cli-args!)
      (trapperkeeper/bootstrap)))
