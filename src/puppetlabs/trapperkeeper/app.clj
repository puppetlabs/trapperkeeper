(ns puppetlabs.trapperkeeper.app
  (:require [plumbing.map]
            [plumbing.graph :refer [eager-compile]]))

;  A type representing a trapperkeeper application.  This is intended to provide
;  an abstraction so that users don't need to worry about the implementation
;  details and can pass the app object to our functions in a type-safe way.
;  The internal properties are not intended to be used outside of this
;  namespace.
(defrecord TrapperKeeperApp [graph-instance])

(def ^{:doc "Alias for plumbing.map/map-leaves-and-path, which is named inconsistently
            with Clojure conventions as it doesn't behave like other `map` functions.
            Map functions typically return a `seq`, never a map, and walk functions
            are used to modify a collection in-place without altering the structure."}
  walk-leaves-and-path plumbing.map/map-leaves-and-path)

(defn service-graph?
  "Predicate that tests whether or not the argument is a valid trapperkeeper
  service graph."
  [service-graph]
  (and
    (map? service-graph)
    (every? keyword? (keys service-graph))
    (every? (some-fn ifn? service-graph?) (vals service-graph))))

(defn validate-service-graph!
  "Validates that the argument is a valid trapperkeeper service graph.  Throws
  an IllegalArgumentException if it is not."
  [service-graph]
  (if (service-graph? service-graph)
    service-graph
    (throw (IllegalArgumentException. (str "Invalid service graph; service graphs must "
                                           "be nested maps of keywords to functions.  Found: "
                                           service-graph)))))

(defn compile-graph
  "Given the merged map of services, compile it into a function suitable for instantiation.
  Throws an exception if there is a dependency on a service that is not found in the map."
  [graph-map]
  {:pre  [(service-graph? graph-map)]
   :post [(ifn? %)]}
  (try
    (eager-compile graph-map)
    (catch IllegalArgumentException e
      ;; TODO: when prismatic releases version 0.2.0 of plumbing, we should clean this
      ;; up.  See: https://tickets.puppetlabs.com/browse/PE-2281
      (let [match (re-matches #"(?s)^Failed on keyseq: \[:(.*)\]\. Value is missing\. .*$" (.getMessage e))]
        (if match
          (throw (RuntimeException. (format "Service function '%s' not found" (second match))))
          (throw e))))))

(defn instantiate
  "Given the compiled graph function, instantiate the application. Throws an exception
  if there is a dependency on a service function that is not found in the graph."
  [graph-fn]
  {:pre  [(ifn? graph-fn)]
   :post [(service-graph? %)]}
  (try
    (graph-fn {})
    (catch RuntimeException e
      ;; TODO: when prismatic releases version 0.2.0 of plumbing, we should clean this
      ;; up.  See: https://tickets.puppetlabs.com/browse/PE-2281
      (if-let [match (re-matches #"^Key (:.*) not found in null$" (.getMessage e))]
        (throw (RuntimeException. (format "Service '%s' not found" (second match))))
        (if-let [match (re-matches #"^Key :(.*) not found in .*$" (.getMessage e))]
          (throw (RuntimeException. (format "Service function '%s' not found" (second match))))
          (throw e))))))

(defn get-service-fn
  "Given a trapperkeeper application, a service name, and a sequence of keys,
  returns the function provided by the service at that path.

  Example:

    (get-service-fn app :logging-service :info)"
  [^TrapperKeeperApp app service service-fn]
  {:pre [(keyword? service)
         (keyword? service-fn)]
   :post [(not (nil? %))
          (ifn? %)]}
  (or
    (get-in (:graph-instance app) [service service-fn])
    (throw (IllegalArgumentException.
             (str "Service " service " or service function " service-fn " not found in graph.")))))
