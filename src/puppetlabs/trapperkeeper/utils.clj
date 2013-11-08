(ns puppetlabs.trapperkeeper.utils)

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
