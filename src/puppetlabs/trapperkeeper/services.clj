(ns puppetlabs.trapperkeeper.services
  (:require [plumbing.core :refer [fnk]]
            [puppetlabs.trapperkeeper.services-internal :as si]
            [schema.core :as schema]
            [puppetlabs.i18n.core :as i18n]))

(defprotocol Lifecycle
  "Lifecycle functions for a service.  All services satisfy this protocol, and
  the lifecycle functions for each service will be called at the appropriate
  phase during the application lifecycle."
  (init [this context] "Initialize the service, given a context map.
                        Must return the (possibly modified) context map.")
  (start [this context] "Start the service, given a context map.
                         Must return the (possibly modified) context map.")
  (stop [this context] "Stop the service, given a context map.
                         Must return the (possibly modified) context map."))

(defprotocol Service
  "Common functions available to all services"
  (service-id [this] "An identifier for the service")
  (service-context [this] "Returns the context map for this service")
  (get-service [this service-id] "Returns the service with the given service id. Throws if service not present")
  (maybe-get-service [this service-id] "Returns the service with the given service id. Returns nil if service not present")
  (get-services [this] "Returns a sequence containing all of the services in the app")
  (service-included? [this service-id] "Returns true or false whether service is included")
  (service-symbol [this] "The namespaced symbol of the service definition, or `nil`
                          if no service symbol was provided."))

(defprotocol ServiceDefinition
  "A service definition.  This protocol is for internal use only.  The service
  is not usable until it is instantiated (via `boot!`)."
  (service-def-id [this] "An identifier for the service")
  (service-map [this] "The map of service functions for the graph"))

(def lifecycle-fn-names (map :name (vals (:sigs Lifecycle))))

(defn name-with-attributes
  "This is a plate of warm and nutritious copypasta of
  clojure.tools.macro/name-with-attributes. Without this modified version,
  name-with-attributes consumes a dependency map when a protocol is not present
  in a defservice invocation. This version of the function double checks a map
  that might be metadata and ignores it if it conforms to the DependencyMap
  schema. Forgive me."
  [name macro-args]
  (let [[docstring macro-args] (if (string? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [nil macro-args])
        [attr macro-args]          (if (and (map? (first macro-args))
                                            (schema/check si/DependencyMap (first macro-args)))
                                     [(first macro-args) (next macro-args)]
                                     [{} macro-args])
        attr                       (if docstring
                                     (assoc attr :doc docstring)
                                     attr)
        attr                       (if (meta name)
                                     (conj (meta name) attr)
                                     attr)]
    [(with-meta name attr) macro-args]))

(defmacro service
  "Create a Trapperkeeper ServiceDefinition.

  First argument (optional) is a protocol indicating the list of functions that
  this service exposes for use by other Trapperkeeper services.

  Second argument is the dependency list; this should be a vector of vectors.
  Each inner vector should begin with a keyword representation of the name of the
  service protocol that the service depends upon.  All remaining items in the inner
  vectors should be symbols representing functions that should be imported from
  the service.

  The remaining arguments should be function definitions for this service, specified
  in the format that is used by a normal clojure `reify`.  The legal list of functions
  that may be specified includes whatever functions are defined by this service's
  protocol (if it has one), plus the list of functions in the `Lifecycle` protocol."
  [& forms]
  (let [{:keys [service-sym service-protocol-sym service-id service-fn-map
                dependencies fns-map]}
        (si/parse-service-forms!
         lifecycle-fn-names
         forms)
        output-schema (si/build-output-schema (keys service-fn-map))]
    `(reify ServiceDefinition
       (service-def-id [this] ~service-id)
       ;; service map for prismatic graph
       (service-map [this]
         {~service-id
          ;; the main service fnk for the app graph.  we add metadata to the fnk
          ;; arguments list to specify an explicit output schema for the fnk
          (fnk service-fnk# :- ~output-schema
            ~(conj dependencies 'tk-app-context 'tk-service-refs)
            (let [svc# (reify
                         Service
                         (service-id [this#] ~service-id)
                         (service-context [this#] (get-in ~'@tk-app-context [:service-contexts ~service-id] {}))
                         (get-service [this# service-id#]
                           (or (get-in ~'@tk-app-context [:services-by-id service-id#])
                               (throw (IllegalArgumentException.
                                        (i18n/trs "Call to ''get-service'' failed; service ''{0}'' does not exist."
                                          service-id#)))))
                         (maybe-get-service [this# service-id#]
                           (get-in ~'@tk-app-context [:services-by-id service-id#] nil))
                         (get-services [this#]
                           (-> ~'@tk-app-context
                               :services-by-id
                               (dissoc :ConfigService :ShutdownService)
                               vals))
                         (service-symbol [this#] '~service-sym)
                         (service-included? [this# service-id#]
                           (not (nil? (get-in ~'@tk-app-context [:services-by-id service-id#] nil))))

                         Lifecycle
                         ~@(si/fn-defs fns-map lifecycle-fn-names)

                         ~@(when service-protocol-sym
                             `(~service-protocol-sym
                                ~@(si/fn-defs fns-map (vals service-fn-map)))))]
              (swap! ~'tk-service-refs assoc ~service-id svc#)
              (si/build-service-map ~service-fn-map svc#)))}))))

(defmacro defservice
  [svc-name & forms]
  (let [service-sym      (symbol (name (ns-name *ns*)) (name svc-name))
        [svc-name forms] (name-with-attributes svc-name forms)]
    `(def ~svc-name (service {:service-symbol ~service-sym} ~@forms))))

