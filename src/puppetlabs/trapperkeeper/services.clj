(ns puppetlabs.trapperkeeper.services
  (:import (clojure.lang IFn))
  (:require [clojure.set :as set]
            [clojure.tools.macro :as macro]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as schema]
            [plumbing.core :as plumbing]
            [plumbing.graph :as graph]))

(def ServiceSymbol
  "For internal use only; this schema gives us a way to differentiate between
  a symbol representing the name of a service, and a symbol representing the
  name of the *protocol* for a service.  This is necessary because the `service`
  macro accepts both as optional leading arguments when defining a service."
  {:service-symbol (schema/pred symbol?)})

(defn protocol?
  "A predicate to determine whether or not an object is a protocol definition"
  [p]
  ;; there might be a better way to do this, but it seems to work
  (and (map? p)
       (contains? p :on)
       (instance? Class (resolve (:on p)))))

(defn fn-sig?
  "A predicate to determine whether or not a form represents a valid function signature
  in the context of a service definition."
  [sig]
  (and (seq? sig)
       (> (count sig) 1)
       (symbol? (first sig))
       (vector? (second sig))))

(defn fns-map?
  "A predicate to determine whether or not an object is a map of fns, as
  used internally by the `service` macro"
  [m]
  (and (map? m)
       (every? keyword? (keys m))
       (every? seq? (vals m))
       (every? fn-sig? (apply concat (vals m)))))

(defn var->symbol
  "Returns a symbol for the var, including its namespace"
  [fn-var]
  {:pre [(var? fn-var)]
   :post [(symbol? %)]}
  (symbol (str (-> fn-var meta :ns .name))
          (str (-> fn-var meta :name))))

(defn validate-fn-forms!
  "Validate that all of the fn forms in the service body appear to be
  valid fn definitions.  Throws `IllegalArgumentException` otherwise."
  [fns]
  {:pre [(seq? fns)]
   :post [(map? %)
          (= #{:fns} (set (keys %)))]}
  (if (every? seq? fns)
    {:fns fns}
    (throw (IllegalArgumentException.
             (format
               "Invalid service definition; expected function definitions following dependency list, invalid value: '\"hi\"'"
               (pr-str (first (filter #(not (seq? %)) fns))))))))

(defn validate-deps-form!
  "Validate that the service body has a valid dependency specification.
  Throws `IllegalArgumentException` otherwise."
  [forms]
  {:pre [(seq? forms)]
   :post [(map? %)
          (= #{:fns :dependencies} (set (keys %)))]}
  (let [f (first forms)]
    (if (vector? f)
      (merge {:dependencies f} (validate-fn-forms! (rest forms)))
      (throw (IllegalArgumentException.
               (format
                 "Invalid service definition; expected dependency list following protocol, found: '%s'"
                 (pr-str f)))))))

(defn find-prot-and-deps-forms!
  "Given the forms passed to the service macro, find the service protocol
  (if one is provided), the dependency list, and the function definitions.
  Throws `IllegalArgumentException` if the forms do not represent a valid service.
  Returns a map containing the protocol, dependency list, and fn forms."
  [forms]
  {:pre [(seq? forms)]
   :post [(map? %)
          (= #{:fns :dependencies :service-protocol-sym} (set (keys %)))
          ((some-fn nil? symbol?) (:service-protocol-sym %))
          (vector? (:dependencies %))
          (seq? (:fns %))]}
  (let [f (first forms)]
    (cond
      (symbol? f) (merge {:service-protocol-sym f} (validate-deps-form! (rest forms)))
      (vector? f) (merge {:service-protocol-sym nil} (validate-deps-form! forms))
      :else (throw (IllegalArgumentException.
                     (format
                       "Invalid service definition; first form must be protocol or dependency list; found '%s'"
                       (pr-str f)))))))

(defn parse-service-forms!*
  "Given the forms passed to the service macro, find the service symbol (if one
  is provided), the service protocol (if one is provided), the dependency list,
  and the function definitions.  Throws `IllegalArgumentException` if the forms
  do not represent a valid service.  Returns a vector containing the symbol,
  protocol, dependency list, and fn forms."
  [forms]
  {:pre [(seq? forms)]
   :post [(map? %)
          (= #{:fns :dependencies :service-protocol-sym :service-sym}
             (set (keys %)))
          ((some-fn nil? symbol?) (:service-sym %))
          ((some-fn nil? symbol?) (:service-protocol-sym %))
          (vector? (:dependencies %))
          (seq? (:fns %))]}
  (let [f (first forms)]
    (if (nil? (schema/check ServiceSymbol f))
      (merge {:service-sym (get f :service-symbol)} (find-prot-and-deps-forms! (rest forms)))
      (merge {:service-sym nil} (find-prot-and-deps-forms! forms)))))

(defn validate-protocol-sym!
  "Given a var, validate that the var exists and that its value is a protocol.
  Throws `IllegalArgumentException` if the var does not exist or if its value
  is something other than a protocol.  Returns the protocol."
  [sym var]
  {:pre [(symbol? sym)
         ((some-fn nil? var?) var)]
   :post [(protocol? %)]}
  (if-not var
    (throw (IllegalArgumentException.
             (format "Unrecognized service protocol '%s'" sym))))
  (let [protocol (var-get var)]
    (if-not (protocol? protocol)
      (throw (IllegalArgumentException.
               (format "Specified service protocol '%s' does not appear to be a protocol!"
                       sym))))
    protocol))

(defn validate-protocol-fn-names!
  "Validate that the service protocol does not define any functions that have the
  same name as a lifecycle function.  Throws `IllegalArgumentException` if it does."
  [service-protocol-sym service-fn-names lifecycle-fn-names]
  {:pre [(symbol? service-protocol-sym)
         (every? symbol? service-fn-names)
         (every? symbol? lifecycle-fn-names)]}
  (let [collisions (set/intersection (set (map name service-fn-names))
                                 (set (map name lifecycle-fn-names)))]
    (if-not (empty? collisions)
      (throw (IllegalArgumentException.
               (format "Service protocol '%s' includes function named '%s', which conflicts with lifecycle function by same name"
                       (name service-protocol-sym)
                       (first collisions)))))))

(defn validate-provided-fns!
  "Validate that the seq of fns specified in a service body does not include
  any functions that are not part of the service protocol.  Throws `IllegalArgumentException`
  otherwise."
  [service-protocol-sym service-fns provided-fns]
  {:pre [((some-fn nil? symbol?) service-protocol-sym)
         (set? service-fns)
         (every? keyword? service-fns)
         (set? provided-fns)
         (every? keyword? provided-fns)]}
  (if (and (nil? service-protocol-sym)
           (> (count provided-fns) 0))
    (throw (IllegalArgumentException.
             (format
               "Service attempts to define function '%s', but does not provide protocol"
               (name (first provided-fns))))))
  (let [extras (set/difference provided-fns service-fns)]
    (when-not (empty? extras)
      (throw (IllegalArgumentException.
               (format
                 "Service attempts to define function '%s', which does not exist in protocol '%s'"
                 (name (first extras)) (name service-protocol-sym)))))))

(defn validate-required-fns!
  "Given a map of fn forms and a list of required function names,
  validate that all of the required functions are defined.  Throws
  `IllegalArgumentException` otherwise."
  [protocol-sym required-fn-names fns-map]
  {:pre [(fns-map? fns-map)
         ((some-fn nil? coll?) required-fn-names)
         (every? symbol? required-fn-names)
         (symbol? protocol-sym)]}
  (doseq [fn-name required-fn-names]
    (let [fn-name (ks/without-ns (keyword fn-name))]
      (if-not (contains? fns-map fn-name)
        (throw (IllegalArgumentException.
                 (format "Service does not define function '%s', which is required by protocol '%s'"
                         (name fn-name) (name protocol-sym))))))))

(defn add-default-lifecycle-fn
  "Given a map of fns defined by a service, and the name of a lifecycle function,
  check to see if the fns map includes an implementation of the lifecycle function.
  If not, add a default implementation."
  [fns-map fn-name]
  {:pre [(fns-map? fns-map)
         (symbol? fn-name)]
   :post [(fns-map? %)
          (= (ks/keyset %) (conj (ks/keyset fns-map) (keyword fn-name)))]}
  (if (contains? fns-map (keyword fn-name))
    fns-map
    (assoc fns-map (keyword fn-name)
           (list (cons fn-name '([this context] context))))))

(defn add-default-lifecycle-fns
  "Given a map of fns comprising a service body, add in a default implementation
  for any lifecycle functions that are not overridden."
  [lifecycle-fn-names fns-map]
  {:pre [(coll? lifecycle-fn-names)
         (every? symbol? lifecycle-fn-names)
         (fns-map? fns-map)]
   :post [(map? %)
          (= (ks/keyset %)
             (set/union (ks/keyset fns-map)
                    (set (map keyword lifecycle-fn-names))))]}
  (reduce add-default-lifecycle-fn fns-map lifecycle-fn-names))

(defn fn-defs
  "Given a map of all of the function forms from a service definition, and a list
  of function names, return a sequence of all of the forms (including multi-arity forms)
  for the given function names."
  [fns-map fn-names]
  {:pre [(map? fns-map)
         (every? keyword? (keys fns-map))
         (every? seq? (vals fns-map))
         (every? symbol? fn-names)]
   :post [(seq? %)
          (every? seq? %)]}
  (reduce
    (fn [acc fn-name]
      (let [sigs (fns-map (ks/without-ns (keyword fn-name)))]
        (concat acc sigs)))
    '()
    fn-names))

(defn build-service-map
  "Given a map from service protocol function names (keywords) to service
  protocol functions, and a service instance, build up a map of partial
  functions closing over the service instance"
  [service-fn-map svc]
  {:pre [(map? service-fn-map)
         (every? keyword? (keys service-fn-map))
         (every? ifn? (vals service-fn-map))]
   :post [(map? %)
          (every? keyword? (keys %))
          (every? ifn? (vals %))]}
  (into {}
        (map (fn [[fn-name fn-sym]]
               [fn-name (partial fn-sym svc)])
             service-fn-map)))

(defn build-output-schema
  "Given a list of service protocol function names (keywords), build up the
  prismatic output schema for the service (a map from keywords to `IFn`)."
  [service-fn-names]
  {:pre [((some-fn nil? seq?) service-fn-names)
         (every? keyword? service-fn-names)]
   :post [(map? %)
          (every? keyword? (keys %))
          (every? #(= IFn %) (vals %))]}
  (reduce (fn [acc fn-name] (assoc acc fn-name IFn))
          {}
          service-fn-names))

(defn build-fns-map!
  "Given the list of fn forms from the service body, build up a map of
  service fn forms.  The keys of the map will be keyword representations
  of the function names, and the values will be the fn forms.  The final
  map will include default implementations of any lifecycle functions that
  aren't overridden in the service body.  Throws `IllegalArgumentException`
  if the fn forms do not match the protocol."
  [service-protocol-sym service-fn-names lifecycle-fn-names fns]
  {:pre [((some-fn nil? symbol?) service-protocol-sym)
         ((some-fn nil? coll?) service-fn-names)
         (every? symbol? service-fn-names)
         (coll? lifecycle-fn-names)
         (every? symbol? lifecycle-fn-names)
         (every? seq? fns)]
   :post [(fns-map? %)
          (= (ks/keyset %)
             (set/union (set (map (comp ks/without-ns keyword) service-fn-names))
                    (set (map keyword lifecycle-fn-names))))]}

  (when service-protocol-sym
    (validate-protocol-fn-names! service-protocol-sym service-fn-names lifecycle-fn-names))

  (let [fns-map (->> (reduce
                       (fn [acc f]
                         ; second element should be a vector - params to the fn
                         (when-not (vector? (second f))
                           ; macro was used incorrectly - perhaps the user
                           ; mistakenly tried to insert a docstring, like:
                           ; `(service-fn "docs about service-fn..." [this] ... )`
                           (throw
                             (Exception.
                               (str
                                 "Incorrect macro usage: service functions must "
                                 "be defined the same as a call to `reify`, eg: "
                                 "`(my-service-fn [this other-args] ...)`"))))
                         (let [k (keyword (first f))
                               cur (acc k)]
                           (if cur
                             (assoc acc k (cons f cur))
                             (assoc acc k (list f)))))
                       {}
                       fns)
                     (add-default-lifecycle-fns lifecycle-fn-names))]
    (validate-provided-fns!
      service-protocol-sym
      (set (map (comp ks/without-ns keyword) service-fn-names))
      (set/difference (ks/keyset fns-map)
                  (set (map (comp ks/without-ns keyword) lifecycle-fn-names))))
    (when service-protocol-sym
      (validate-required-fns! service-protocol-sym service-fn-names fns-map))
    fns-map))

(defn get-service-id
  "Generate service id based on service protocol symbol.  Returns the keyword of
  the symbol if the symbol is not nil; returns a keyword for a generated symbol
  otherwise."
  [service-protocol-sym]
  {:pre [((some-fn nil? symbol?) service-protocol-sym)]
   :post [(keyword? %)]}
  (ks/without-ns
    (if service-protocol-sym
      (keyword service-protocol-sym)
      (keyword (gensym "tk-service")))))

(defn get-service-fn-map
  "Get a map of service fns based on a protocol.  Keys will be keywords of the
  function names, values will be the protocol functions.  Returns
  an empty map if the protocol symbol is nil."
  [service-protocol-sym]
  {:pre [((some-fn nil? symbol?) service-protocol-sym)]
   :post [(map? %)
          (every? keyword? (keys %))
          (every? symbol? (vals %))]}
  (if service-protocol-sym
    (let [service-protocol-var (resolve service-protocol-sym)
          service-protocol (validate-protocol-sym!
                             service-protocol-sym
                             service-protocol-var)]
      (reduce (fn [acc fn-name]
                (assoc acc (keyword (name fn-name)) fn-name))
              {}
              (mapv var->symbol (keys (:method-builders service-protocol)))))
    {}))

(defn parse-service-forms!
  "Parse the forms provided to the `service` macro.  Return a map
  containing all of the data necessary to implement the macro:

  :service-protocol-sym - the service protocol symbol (or nil if there is no protocol)
  :service-id           - a unique identifier (keyword) for the service
  :service-fn-map       - a map of symbols for the names of the functions provided by the protocol
  :dependencies         - a vector specifying the dependencies of the service
  :fns-map              - a map of all of the fn definition forms in the service"
  [lifecycle-fn-names forms]
  {:pre [(every? symbol? lifecycle-fn-names)
         (seq? forms)]
   :post [(map? %)
          (= #{:service-sym :service-protocol-sym :service-id :service-fn-map
               :dependencies :fns-map} (ks/keyset %))]}
  (let [{:keys [service-sym service-protocol-sym dependencies fns]}
        (parse-service-forms!* forms)
        service-id (get-service-id service-protocol-sym)
        service-fn-map (get-service-fn-map service-protocol-sym)

        fns-map (build-fns-map!
                  service-protocol-sym
                  (vals service-fn-map)
                  lifecycle-fn-names
                  fns)]

    {:service-sym          service-sym
     :service-protocol-sym service-protocol-sym
     :service-id           service-id
     :service-fn-map       service-fn-map
     :dependencies         dependencies
     :fns-map              fns-map}))

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
  (get-service [this service-id] "Returns the service with the given service id")
  (get-services [this] "Returns a sequence containing all of the services in the app")
  (service-symbol [this] "The namespaced symbol of the service definition, or `nil`
                          if no service symbol was provided."))

(defprotocol ServiceDefinition
  "A service definition.  This protocol is for internal use only.  The service
  is not usable until it is instantiated (via `boot!`)."
  (service-def-id [this] "An identifier for the service")
  (service-map [this] "The map of service functions for the graph"))

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
  (let [{:keys [service-sym service-protocol-sym service-id service-fn-map
                dependencies fns-map]}
        (parse-service-forms!
          lifecycle-fn-names
          forms)
        output-schema (build-output-schema (keys service-fn-map))]
    `(reify ServiceDefinition
       (service-def-id [this] ~service-id)
       ;; service map for prismatic graph
       (service-map [this]
         {~service-id
           ;; the main service fnk for the app graph.  we add metadata to the fnk
           ;; arguments list to specify an explicit output schema for the fnk
           (plumbing/fnk service-fnk# :- ~output-schema
                ~(conj dependencies 'tk-app-context 'tk-service-refs)
                (let [svc# (reify
                             Service
                             (service-id [this#] ~service-id)
                             (service-context [this#] (get ~'@tk-app-context ~service-id {}))
                             (get-service [this# service-id#]
                               (or (get-in ~'@tk-app-context [:services-by-id service-id#])
                                   (throw (IllegalArgumentException.
                                            (format
                                              "Call to 'get-service' failed; service '%s' does not exist."
                                              service-id#)))))
                             (get-services [this#]
                               (-> ~'@tk-app-context
                                   :services-by-id
                                   (dissoc :ConfigService :ShutdownService)
                                   vals))
                             (service-symbol [this#] '~service-sym)

                             Lifecycle
                             ~@(fn-defs fns-map lifecycle-fn-names)

                             ~@(if service-protocol-sym
                                 `(~service-protocol-sym
                                   ~@(fn-defs fns-map (vals service-fn-map)))))]
                  (swap! ~'tk-service-refs assoc ~service-id svc#)
                  (build-service-map ~service-fn-map svc#)))}))))

(defmacro defservice
  [svc-name & forms]
  (let [service-sym      (symbol (name (ns-name *ns*)) (name svc-name))
        [svc-name forms] (macro/name-with-attributes svc-name forms)]
    `(def ~svc-name (service {:service-symbol ~service-sym} ~@forms))))

