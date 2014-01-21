(ns puppetlabs.trapperkeeper.core
  (:require [clojure.tools.logging :as log]
            [slingshot.slingshot :refer [try+]]
            [plumbing.graph :as g]
            [puppetlabs.kitchensink.core :refer [without-ns]]
            [puppetlabs.trapperkeeper.services :as s]
            [puppetlabs.trapperkeeper.app :as a]
            [puppetlabs.trapperkeeper.bootstrap :as b]
            [puppetlabs.trapperkeeper.internal :as i]
            [puppetlabs.trapperkeeper.config :as c]
            [puppetlabs.trapperkeeper.plugins :as p]))

(def #^{:macro true
        :doc "An alias for the `puppetlabs.trapperkeeper.services/service` macro
             so that it is accessible from the core namespace along with the
             rest of the API."}
  service #'s/service)

(def #^{:macro true
        :doc "An alias for the `puppetlabs.trapperkeeper.services/defservice` macro
             so that it is accessible from the core namespace along with the
             rest of the API."}
  defservice #'s/defservice)

(defn build-app
  ;; TODO docs
  "Given a list of services and a map of configuration data, build an instance
  of a TrapperkeeperApp.  Services are not yet initialized or started."
  [services config-data]
  {:pre  [(sequential? services)
          (every? #(satisfies? s/ServiceDefinition %) services)
          (map? config-data)]
   :post [(satisfies? a/TrapperkeeperApp %)]}
  (c/initialize-logging! config-data)
  (i/build-app* services config-data))

(defn boot-services-with-cli-data
  ;; TODO DOCS
  [services cli-data]
  {:pre  [(sequential? services)
          (every? #(satisfies? s/ServiceDefinition %) services)
          (map? cli-data)]
   :post [(satisfies? a/TrapperkeeperApp %)]}
  (let [config-data (c/parse-config-data cli-data)]
    (c/initialize-logging! config-data)
    ;; TODO: I don't think we need to do plugin stuff if they are passing
    ;; us instances of ServiceDefinition
    #_(p/add-plugin-jars-to-classpath! (cli-data :plugins))
    ;; TODO: I don't think we need to do bootstrap-config stuff if they
    ;; are passing us instances of ServiceDefinition
    #_(-> cli-data
        (b/find-bootstrap-config)
        (b/parse-bootstrap-config!)
        (i/boot-services* config-data))
    (i/boot-services* services config-data)))

(defn boot-services-with-config
  ;; TODO DOCS
  "Given the services to run and command-line arguments,
   bootstrap and return the trapperkeeper application."
  [services config-data]
  {:pre  [(sequential? services)
          (every? #(satisfies? s/ServiceDefinition %) services)
          (map? config-data)]
   :post [(satisfies? a/TrapperkeeperApp %)]}
  (c/initialize-logging! config-data)
  ;; TODO: I don't think we need to do plugin stuff if they are passing
  ;; us instances of ServiceDefinition
  #_(p/add-plugin-jars-to-classpath! (cli-data :plugins))
  (i/boot-services* services config-data))

(defn boot-with-cli-data
  ;; TODO docs
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
   :post [(satisfies? a/TrapperkeeperApp %)]}
  ;; There is a strict order of operations that need to happen here:
  ;; 1. parse config files
  ;; 2. initialize logging
  ;; 3. initialize plugin system
  ;; 4. bootstrap rest of framework
  (let [config-data (c/parse-config-data cli-data)]
    (c/initialize-logging! config-data)
    (p/add-plugin-jars-to-classpath! (cli-data :plugins))
    (-> cli-data
        (b/find-bootstrap-config)
        (b/parse-bootstrap-config!)
        (i/boot-services* config-data))))


;; TODO docs
(def main-app nil)

(defn run
  "Bootstraps a trapperkeeper application and runs it.
  Blocks the calling thread until trapperkeeper is shut down.
  `cli-data` is expected to be a map constructed by parsing the CLI args.
  (see `parse-cli-args`)"
  [cli-data]
  {:pre [(map? cli-data)]}
  (let [app (boot-with-cli-data cli-data)]
    ;; TODO docs
    (alter-var-root #'main-app (fn [_] app))
    (i/run-app app)))

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
        (i/parse-cli-args!)
        (run))
    (catch map? m
      (println (:message m))
      (case (without-ns (:type m))
        :cli-error (System/exit 1)
        :cli-help  (System/exit 0)))))
