(ns puppetlabs.trapperkeeper.core
  (:require puppetlabs.trapperkeeper.app
            [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.kitchensink.core :refer [cli! without-ns]]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.shutdown :refer [wait-for-shutdown shutdown!
                                                       initiated-internally? call-error-handler!]])
  (:import (puppetlabs.trapperkeeper.app TrapperKeeperApp)))

(def #^{:macro true
        :doc "An alias for the `puppetlabs.trapperkeeper.services/service` macro
             so that it is accessible from the core namespace along with the
             rest of the API."}
  service #'services/service)

(def #^{:macro true
        :doc "An alias for the `puppetlabs.trapperkeeper.services/defservice` macro
             so that it is accessible from the core namespace along with the
             rest of the API."}
  defservice #'services/defservice)

(defn run-app
  "Given a bootstrapped TrapperKeeper app, let the application run until shut down,
  which may be triggered by one of several different ways. In all cases, services
  will be shut down and any exceptions they might throw will be caught and logged."
  [^TrapperKeeperApp app]
  {:pre [(instance? TrapperKeeperApp app)]}
  (let [shutdown-reason (wait-for-shutdown app)]
    (when (initiated-internally? shutdown-reason)
      (call-error-handler! shutdown-reason)
      (shutdown!)
      (when-let [error (:error shutdown-reason)]
        (throw error)))))

(defn bootstrap
  "Bootstrap a trapperkeeper application.  This is accomplished by reading a
  bootstrap configuration file containing a list of (namespace-qualified)
  service functions.  These functions will be called to generate a service
  graph for the application; dependency resolution between the services will
  be handled automatically to ensure that they are started in the correct order.
  Functions that a service expresses dependencies on will be injected prior to
  instantiation of a service.

  The bootstrap config file will be searched for in this order:

  * At a path specified by the optional command-line argument `--bootstrap-config`
  * In the current working directory, in a file named `bootstrap.cfg`
  * On the classpath, in a file named `bootstrap.cfg`.

  `cli-data` is a map of the command-line arguments and their values.
  `puppetlabs.kitchensink/cli!` can handle the parsing for you.

  Their must be a `:config` key in this map which defines the .ini file
  (or directory of files) used by the configuration service."
  [cli-data]
  {:pre  [(map? cli-data)
          (contains? cli-data :config)]
   :post [(instance? TrapperKeeperApp %)]}
  (bootstrap/bootstrap cli-data))

(defn run
  "Bootstraps a trapperkeeper application and runs it.
  Blocks the calling thread until trapperkeeper is shut down.
  `cli-data` is expected to be a map constructed by parsing the CLI args.
  (see `parse-cli-args`)"
  [cli-data]
  {:pre [(map? cli-data)]}
  (-> cli-data
      (bootstrap)
      (run-app)))

(defn parse-cli-args!
  "Parses the command-line arguments using `puppetlabs.kitchensink.core/cli!`.
  Hard-codes the command-line arguments expected by trapperkeeper to be:
      --debug
      --bootstrap-config <bootstrap file>
      --config <.ini file or directory>"
  [cli-args]
  {:pre  [(sequential? cli-args)]
   :post [(map? %)]}
  (let [specs    [["-d" "--debug" "Turns on debug mode" :flag true]
                  ["-b" "--bootstrap-config" "Path to bootstrap config file"]
                  ["-c" "--config" "Path to .ini file or directory of .ini files to be read and consumed by services"]]
        required [:config]]
    (first (cli! cli-args specs required))))

(defn main
  "Launches the trapperkeeper framework. This function blocks until
  trapperkeeper is shut down. This may be called directly, but is also called by
  `puppetlabs.trapperkeeper.core/-main` if you use `puppetlabs.trapperkeeper.core`
  as the `:main` namespace in your leinengen project."
  [& args]
  {:pre [((some-fn sequential? nil?) args)
         (every? string? args)]}
  (try+
    (-> args
        (parse-cli-args!)
        (run))
    (catch map? m
      (println (:message m))
      (case (without-ns (:type m))
        :cli-error (System/exit 1)
        :cli-help  (System/exit 0)))))
