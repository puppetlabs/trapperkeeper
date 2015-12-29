(ns puppetlabs.trapperkeeper.core
  (:require [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.kitchensink.core :refer [without-ns]]
            [puppetlabs.trapperkeeper.services :as services]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.trapperkeeper.plugins :as plugins]))

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

(defn build-app
  "Given a list of services and a map of configuration data, build an instance
  of a TrapperkeeperApp.  Services are not yet initialized or started.  This
  function is mainly intended for use in a REPL, for developing using the
  'reloaded' pattern.
  ( http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded )

  Returns a TrapperkeeperApp instance.  You may call the lifecycle functions
  (`init`, `start`, `stop`) as you see fit;  if you'd like to have the trapperkeeper
  framework block the main thread to wait for a shutdown event, call `init`,
  `start`, and then `run-app`."
  [services config-data]
  {:pre  [(sequential? services)
          (every? #(satisfies? services/ServiceDefinition %) services)
          (map? config-data)]
   :post [(satisfies? app/TrapperkeeperApp %)]}
  (config/initialize-logging! config-data)
  (internal/build-app* services config-data (promise)))

(defn boot-services-with-cli-data
  "Given a list of ServiceDefinitions and a map containing parsed cli data, create
  and boot a trapperkeeper app.  This function can be used if you prefer to
  do your own CLI parsing and loading ServiceDefinitions; it circumvents
  the normal trapperkeeper `bootstrap.cfg` boot process, but still allows
  trapperkeeper to handle the parsing of your service configuration data.

  Returns a TrapperkeeperApp instance.  Call `run-app` on it if you'd like to
  block the main thread to wait for a shutdown event."
  [services cli-data]
  {:pre  [(sequential? services)
          (every? #(satisfies? services/ServiceDefinition %) services)
          (map? cli-data)]
   :post [(satisfies? app/TrapperkeeperApp %)]}
  (let [config-data (config/parse-config-data cli-data)]
    (config/initialize-logging! config-data)
    (internal/boot-services* services config-data)))

(defn boot-services-with-config
  "Given a list of ServiceDefinitions and a map containing parsed cli data, create
  and boot a trapperkeeper app.  This function can be used if you prefer to
  do your own CLI parsing and loading ServiceDefinitions; it circumvents
  the normal trapperkeeper `bootstrap.cfg` boot process, but still allows
  trapperkeeper to handle the parsing of your service configuration data.

  Returns a TrapperkeeperApp instance.  Call `run-app` on it if you'd like to
  block the main thread to wait for a shutdown event."
  [services config-data]
  {:pre  [(sequential? services)
          (every? #(satisfies? services/ServiceDefinition %) services)
          (map? config-data)]
   :post [(satisfies? app/TrapperkeeperApp %)]}
  (config/initialize-logging! config-data)
  (internal/boot-services* services config-data))

(defn boot-with-cli-data
  "Create and boot a trapperkeeper application.  This is accomplished by reading a
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

  `preprocess-config-fn` is a function through which the config data is passed
  after it is parsed. If not provided, it defaults to `identity`.

  Their must be a `:config` key in this map which defines the .ini file
  (or directory of files) used by the configuration service.

  Returns a TrapperkeeperApp instance.  Call `run-app` on it if you'd like to
  block the main thread to wait for a shutdown event."
  ([cli-data preprocess-config-fn]
   {:pre  [(map? cli-data)
           (fn? preprocess-config-fn)]
    :post [(satisfies? app/TrapperkeeperApp %)]}
   ;; There is a strict order of operations that need to happen here:
   ;; 1. parse config files
   ;; 2. initialize logging
   ;; 3. initialize plugin system
   ;; 4. bootstrap rest of framework
   (let [config-data (preprocess-config-fn (config/parse-config-data cli-data))]
     (config/initialize-logging! config-data)
     (plugins/add-plugin-jars-to-classpath! (cli-data :plugins))
     (-> cli-data
         (bootstrap/find-bootstrap-config)
         (bootstrap/parse-bootstrap-config!)
         (internal/boot-services* config-data))))
  ([cli-data]
   (boot-with-cli-data cli-data identity)))


;; This variable is used to hold a reference to the "main" trapperkeeper
;; application instance if you use trapperkeeper's "main" function to
;; launch your application.  This allows you to get a reference to the
;; app in a remote nREPL session if you are using the nrepl service.
(def main-app nil)

(defn run-app
  "Given a bootstrapped TrapperKeeper app, let the application run until shut down,
  which may be triggered by one of several different ways. In all cases, services
  will be shut down and any exceptions they might throw will be caught and logged."
  [app]
  {:pre [(satisfies? app/TrapperkeeperApp app)]}
  (let [shutdown-reason (internal/wait-for-app-shutdown app)]
    (when (internal/initiated-internally? shutdown-reason)
      (internal/call-error-handler! shutdown-reason)
      (internal/shutdown! (app/app-context app))
      (when-let [error (:error shutdown-reason)]
        (throw error)))))

(defn run
  "Bootstraps a trapperkeeper application and runs it.
  Blocks the calling thread until trapperkeeper is shut down.
  `cli-data` is expected to be a map constructed by parsing the CLI args.
  (see `parse-cli-args`)"
  ([cli-data preprocess-config-fn]
   {:pre [(map? cli-data)
          (fn? preprocess-config-fn)]}
   (let [app (boot-with-cli-data cli-data preprocess-config-fn)]
     ;; This line populates the `main-app` variable with the TrapperkeeperApp
     ;; instance.  This allows it to be referenced in a remote nREPL session.
     (alter-var-root #'main-app (fn [_] app))
     (run-app app)))
  ([cli-data] (run cli-data identity)))

(defn main-with-config-hook
  "A version of the main function that allows the caller to provide a function
  to preprocess the parsed config data structure before it is passed to any
  services."
  [args preprocess-config-fn]
  {:pre [((some-fn sequential? nil?) args)
         (every? string? args)
         (fn? preprocess-config-fn)]}
  (let [quit (fn [status msg stream]
               (binding [*out* stream] (println msg) (flush))
               (System/exit status))]
    (try+
     (-> (or args '())
         (internal/parse-cli-args!)
         (run preprocess-config-fn))
     (catch map? m
       (case (without-ns (:type m))
         :cli-error (quit 1 (:message m) *err*)
         :cli-help (quit 0 (:message m) *out*)
         (throw+)))
     (finally
       (shutdown-agents)))))

(defn main
  "Launches the trapperkeeper framework. This function blocks until
  trapperkeeper is shut down. This may be called directly, but is also called by
  `puppetlabs.trapperkeeper.core/-main` if you use `puppetlabs.trapperkeeper.core`
  as the `:main` namespace in your leinengen project."
  [& args]
  {:pre [((some-fn sequential? nil?) args)
         (every? string? args)]}
  (main-with-config-hook args identity))
