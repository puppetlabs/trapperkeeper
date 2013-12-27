(ns puppetlabs.trapperkeeper.experimental.services
  (:require [clojure.tools.macro :refer [name-with-attributes]]
            [clojure.set :refer [difference]]
            [plumbing.core :refer [fnk]]
            [plumbing.graph :as g]
            [puppetlabs.kitchensink.core :refer [select-values keyset]]
            [puppetlabs.trapperkeeper.experimental.services-internal :as si]))

(defprotocol ServiceLifecycle
  "Lifecycle functions for a service.  All services satisfy this protocol, and
  the lifecycle functions for each service will be called at the appropriate
  phase during the application lifecycle."
  (init [this context] "Initialize the service, given a context map.
                        Must return the (possibly modified) context map.")
  (start [this context] "Start the service, given a context map.
                         Must return the (possibly modified) context map."))

(defprotocol Service
  "Common functions available to all services"
  (service-context [this] "Returns the context map for this service"))

(defprotocol TrapperkeeperApp
  "Functions available on a trapperkeeper application instance"
  (get-service [this service-id] "Returns the service with the given service id"))

(defprotocol ServiceDefinition
  "A service definition.  This protocol is for internal use only.  The service
  is not usable until it is instantiated (via `boot!`)."
  (service-id [this] "An identifier for the service")
  (service-map [this] "The map of service functions for the graph")
  (constructor [this] "A constructor function to instantiate the service"))

(def lifecycle-fn-names (map :name (vals (:sigs ServiceLifecycle))))

(defmacro service
  ;; TODO DOCS
  [& forms]
  (let [{:keys [service-protocol-sym service-id service-fn-names
                dependencies fns-map]}
                      (si/parse-service-forms!
                        lifecycle-fn-names
                        forms)
        ;;; we add 'context' to the dependencies list of all of the services.  we'll
        ;;; use this to inject the service context so it's accessible from service functions
        dependencies  (conj dependencies 'context)]
    `(reify ServiceDefinition
       (service-id [this] ~service-id)

       ;; service map for prismatic graph
       (service-map [this]
         {~service-id
           ;; the main service fnk for the app graph.  we add metadata to the fnk
           ;; arguments list to specify an explicit output schema for the fnk
           (fnk ~(si/fnk-binding-form dependencies service-fn-names)
              ;; create a function that exposes the service context to the service.
              (let [~'service-context (fn [] (get ~'@context ~service-id))
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
       (constructor [this]
         ;; the constructor requires the main app graph and context atom as input
         (fn [graph# context#]
           (let [~'service-fns (graph# ~service-id)]
             ;; now we instantiate the service and define all of its protocol functions
             (reify
               Service
               (service-context [this] (get @context# ~service-id {}))

               ServiceLifecycle
               ~@(si/protocol-fns lifecycle-fn-names fns-map)

               ~@(if service-protocol-sym
                  `(~service-protocol-sym
                    ~@(si/protocol-fns service-fn-names fns-map))))))))))

(defmacro defservice
  [svc-name & forms]
  (let [[svc-name forms] (name-with-attributes svc-name forms)]
    `(def ~svc-name (service ~@forms))))

(defn boot!
  ;; TODO docs
  [services]
  {:pre [(every? #(satisfies? ServiceDefinition %) services)]
   :post [(satisfies? TrapperkeeperApp %)]}
  (let [service-map    (apply merge (map service-map services))
        ;; this gives us an ordered graph that we can use to call lifecycle
        ;; functions in the correct order later
        graph          (g/->graph service-map)
        compiled-graph (g/eager-compile graph)
        ;; this is the application context for this app instance.  its keys
        ;; will be the service ids, and values will be maps that represent the
        ;; context for each individual service
        context        (atom {})
        ;; when we instantiate the graph, we pass in the context atom.
        graph-instance (compiled-graph {:context context})
        ;; here we build up a map of all of the services by calling the
        ;; constructor for each one
        services-by-id (into {} (map
                                  (fn [sd] [(service-id sd)
                                            ((constructor sd) graph-instance context)])
                                  services))
        ;; finally, create the app instance
        app            (reify
                         TrapperkeeperApp
                         (get-service [this protocol] (services-by-id (keyword protocol))))]

    ;; iterate over the lifecycle functions in order
    (doseq [[lifecycle-fn lifecycle-fn-name]  [[init "init"] [start "start"]]
            ;; and iterate over the services, based on the ordered graph so
            ;; that we know their dependencies are taken into account
            graph-entry                       graph]

      (let [service-id    (first graph-entry)
            s             (services-by-id service-id)
            ;; call the lifecycle function on the service, and keep a reference
            ;; to the updated context map that it returns
            updated-ctxt  (lifecycle-fn s (get @context service-id {}))]
        (if-not (map? updated-ctxt)
          (throw (IllegalStateException.
                   (format
                     "Lifecycle function '%s' for service '%s' must return a context map (got: %s)"
                     lifecycle-fn-name
                     service-id
                     (pr-str updated-ctxt)))))
        ;; store the updated service context map in the application context atom
        (swap! context assoc service-id updated-ctxt)))
    app))

