(ns puppetlabs.trapperkeeper.services
  (:require [clojure.tools.macro :refer [name-with-attributes]]
            [clojure.set :refer [difference]]
            [plumbing.core :refer [fnk]]
            [schema.core :as schema]
            [plumbing.graph :as g]
            [puppetlabs.kitchensink.core :refer [select-values keyset]]
            [puppetlabs.trapperkeeper.services-internal :as si]))

;; Look into re-using an existing protocol for the life cycle instead of
;; creating our own; just didn't want to introduce the dependency for now.
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
  (service-context [this] "Returns the context map for this service"))

(defprotocol ServiceDefinition
  "A service definition.  This protocol is for internal use only.  The service
  is not usable until it is instantiated (via `boot!`)."
  (service-id [this] "An identifier for the service")
  (service-map [this] "The map of service functions for the graph")
  (service-constructor [this] "A constructor function to instantiate the service"))

(def lifecycle-fn-names (map :name (vals (:sigs Lifecycle))))

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
  (let [{:keys [service-protocol-sym service-id service-fn-names
                dependencies fns-map]}
                      (si/parse-service-forms!
                        lifecycle-fn-names
                        forms)
        ;;; we add 'context' to the dependencies list of all of the services.  we'll
        ;;; use this to inject the service context so it's accessible from service functions
        dependencies  (conj dependencies 'context)
        output-schema (into {}
                            (map (fn [f] [(keyword f) schema/Any]) service-fn-names))]
    `(reify ServiceDefinition
       (service-id [this] ~service-id)

       ;; service map for prismatic graph
       (service-map [this]
         {~service-id
           ;; the main service fnk for the app graph.  we add metadata to the fnk
           ;; arguments list to specify an explicit output schema for the fnk
           (fnk f :- ~output-schema
              ~dependencies
              ;; create a function that exposes the service context to the service.
              (let [~'service-context (fn [] (get ~'@context ~service-id))
                    ~'service-id      (fn [] ~service-id)
                    ;; here we create an inner graph for this service.  we need
                    ;; this in order to handle deps within a single service.
                    service-map#      ~(si/prismatic-service-map
                                         (concat lifecycle-fn-names service-fn-names)
                                         fns-map)
                    s-graph-inst#     (if (empty? service-map#)
                                        {}
                                        ((g/eager-compile service-map#) {}))]
                s-graph-inst#))})

       ;; service constructor function
       (service-constructor [this]
         ;; the constructor requires the main app graph and context atom as input
         (fn [graph# context#]
           (let [~'service-fns (graph# ~service-id)]
             ;; now we instantiate the service and define all of its protocol functions
             (reify
               Service
               (service-context [this] (get @context# ~service-id {}))

               Lifecycle
               ~@(si/protocol-fns lifecycle-fn-names fns-map)

               ~@(if service-protocol-sym
                  `(~service-protocol-sym
                    ~@(si/protocol-fns service-fn-names fns-map))))))))))

(defmacro defservice
  [svc-name & forms]
  (let [[svc-name forms] (name-with-attributes svc-name forms)]
    `(def ~svc-name (service ~@forms))))

