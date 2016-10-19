(ns puppetlabs.trapperkeeper.services-internal
  (:import (clojure.lang IFn))
  (:require [clojure.walk :refer [postwalk]]
            [clojure.set :refer [difference union intersection]]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.i18n.core :as i18n]))

(def optional-dep-default
  "This value is used in place of an optional service
   when the service is not included. In the future, it may be a more interesting or
   useful dummy service."
  nil)

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

(def Protocol
  "A schema to determine whether or not an object is a protocol definition."
  (schema/pred protocol?))

(def Symbol
  "Interal schema type for validating symbol values."
  (schema/pred symbol?))

(def Var
  "Interal schema type for validating var values."
  (schema/pred var?))

(def ServiceFnMap
  "Internal schema for a service's map of function references."
  {schema/Keyword IFn})

(def FnsMap
  "Internal schema for a map of a service's function body forms."
  {schema/Keyword [(schema/pred fn-sig?)]})

(def InternalServiceMap
  "Schema defining TK's internal representation of a service. This is used while
  processing the service macro."
  {:service-sym (schema/maybe Symbol)
   :service-protocol-sym (schema/maybe Symbol)
   :service-id schema/Keyword
   :service-fn-map ServiceFnMap
   :dependencies (schema/pred sequential?)
   :fns-map FnsMap})

(def ProtocolandDependenciesMap
  "Schema defining the map used to represent the protocol and dependencies
  of a service."
  {:fns [(schema/pred seq?)]
   :dependencies (schema/pred vector?)
   :service-protocol-sym (schema/maybe Symbol)})

(def ServiceMap
  "Schema defining the map used to represent a schema in the output service
   map."
  {schema/Keyword IFn})

(def DependencyMap
  "Schema for a map detailing optional vs. required dependencies for a
   service."
  {:optional (schema/pred vector?)
   :required (schema/pred vector?)})

(schema/defn ^:always-validate var->symbol :- Symbol
  "Returns a symbol for the var, including its namespace"
  [fn-var :- Var]
  (symbol (str (-> fn-var meta :ns .name))
          (str (-> fn-var meta :name))))

(schema/defn ^:always-validate transform-deps-map :- (schema/pred vector?)
  "Given a map of required and optional dependencies, return a vector that
  adheres to Prismatic Graph's binding syntax. Specifically, optional
  dependencies are transformed into {ServiceName nil}."
  [deps :- DependencyMap]
  (loop [optional (:optional deps) output (:required deps)]
    (if (empty? optional)
      output
      (let [dep (first optional)
            optional-form (array-map dep optional-dep-default)]
        (recur (rest optional) (conj output optional-form))))))

(schema/defn ^:always-validate find-prot-and-deps-forms! :- ProtocolandDependenciesMap
  "Given the forms passed to the service macro, find the service protocol
  (if one is provided), the dependency list, and the function definitions.
  Throws `IllegalArgumentException` if the forms do not represent a valid service.
  Returns a map containing the protocol, dependency list, and fn forms."
  [forms :- (schema/pred seq?)]
  (let [f (if ((some-fn symbol? map? vector?) (first forms))
            (first forms)
            (throw (IllegalArgumentException.
                     (i18n/trs "Invalid service definition; first form must be protocol or dependency list; found ''{0}''"
                               (pr-str (first forms))))))
        service-protocol-sym (if (symbol? f) f nil)
        forms (if (nil? service-protocol-sym) forms (rest forms))
        ff (first forms)
        deps (cond (vector? ff) ff
                   (map? ff) (transform-deps-map ff)
                   :else (throw (IllegalArgumentException.
                                  (i18n/trs "Invalid service definition; expected dependency list following protocol, found: ''{0}''"
                                            (pr-str ff)))))]
    (if (every? seq? (rest forms))
      {:service-protocol-sym service-protocol-sym
       :dependencies deps
       :fns (rest forms)}
      (throw (IllegalArgumentException.
               (i18n/trs "Invalid service definition; expected function definitions following dependency list, invalid value: ''{0}''"
                         (pr-str (first (filter #(not (seq? %)) (rest forms))))))))))

(schema/defn ^:always-validate validate-protocol-sym! :- Protocol
  "Given a var, validate that the var exists and that its value is a protocol.
  Throws `IllegalArgumentException` if the var does not exist or if its value
  is something other than a protocol.  Returns the protocol."
  [sym :- Symbol
   var :- (schema/maybe Var)]
  (if-not var
    (throw (IllegalArgumentException.
            (i18n/trs "Unrecognized service protocol ''{0}''" sym))))
  (let [protocol (var-get var)]
    (if-not (protocol? protocol)
      (throw (IllegalArgumentException.
               (i18n/trs "Specified service protocol ''{0}'' does not appear to be a protocol!"
                         sym))))
    protocol))

(schema/defn ^:always-validate validate-protocol-fn-names! :- nil
  "Validate that the service protocol does not define any functions that have the
  same name as a lifecycle function.  Throws `IllegalArgumentException` if it does."
  [service-protocol-sym :- Symbol
   service-fn-names :- [Symbol]
   lifecycle-fn-names :- [Symbol]]
  (let [collisions (intersection (set (map name service-fn-names))
                                 (set (map name lifecycle-fn-names)))]
    (if-not (empty? collisions)
      (throw (IllegalArgumentException.
               (i18n/trs "Service protocol ''{0}'' includes function named ''{1}'', which conflicts with lifecycle function by same name"
                         (name service-protocol-sym)
                         (first collisions)))))))

(schema/defn ^:always-validate validate-provided-fns! :- nil
  "Validate that the seq of fns specified in a service body does not include
  any functions that are not part of the service protocol.  Throws `IllegalArgumentException`
  otherwise."
  [service-protocol-sym :- (schema/maybe Symbol)
   service-fns :- #{schema/Keyword}
   provided-fns :- #{schema/Keyword}]
  (if (and (nil? service-protocol-sym)
           (> (count provided-fns) 0))
    (throw (IllegalArgumentException.
             (i18n/trs "Service attempts to define function ''{0}'', but does not provide protocol"
                       (name (first provided-fns))))))
  (let [extras (difference provided-fns service-fns)]
    (when-not (empty? extras)
      (throw (IllegalArgumentException.
               (i18n/trs "Service attempts to define function ''{0}'', which does not exist in protocol ''{1}''"
                         (name (first extras)) (name service-protocol-sym)))))))

(schema/defn ^:always-validate validate-required-fns! :- nil
  "Given a map of fn forms and a list of required function names,
  validate that all of the required functions are defined.  Throws
  `IllegalArgumentException` otherwise."
  [protocol-sym :- Symbol
   required-fn-names :- (schema/maybe [Symbol])
   fns-map :- FnsMap]
  (doseq [fn-name required-fn-names]
    (let [fn-name (ks/without-ns (keyword fn-name))]
      (if-not (contains? fns-map fn-name)
        (throw (IllegalArgumentException.
                 (i18n/trs "Service does not define function ''{0}'', which is required by protocol ''{1}''"
                           (name fn-name) (name protocol-sym))))))))

(schema/defn ^:always-validate add-default-lifecycle-fn :- FnsMap
  "Given a map of fns defined by a service, and the name of a lifecycle function,
  check to see if the fns map includes an implementation of the lifecycle function.
  If not, add a default implementation."
  [fns-map :- FnsMap
   fn-name :- Symbol]
  {:post [(= (ks/keyset %) (conj (ks/keyset fns-map) (keyword fn-name)))]}
  (if (contains? fns-map (keyword fn-name))
    fns-map
    (assoc fns-map (keyword fn-name)
           (list (cons fn-name '([this context] context))))))

(schema/defn ^:always-validate add-default-lifecycle-fns :- FnsMap
  "Given a map of fns comprising a service body, add in a default implementation
  for any lifecycle functions that are not overridden."
  [lifecycle-fn-names :- [Symbol]
   fns-map :- FnsMap]
  {:post [(= (ks/keyset %)
             (union (ks/keyset fns-map)
                    (set (map keyword lifecycle-fn-names))))]}
  (reduce add-default-lifecycle-fn fns-map lifecycle-fn-names))

(schema/defn ^:always-validate fn-defs :- [(schema/pred seq?)]
  "Given a map of all of the function forms from a service definition, and a list
  of function names, return a sequence of all of the forms (including multi-arity forms)
  for the given function names."
  [fns-map :- FnsMap
   fn-names :- [Symbol]]
  (reduce
   (fn [acc fn-name]
     (let [sigs (fns-map (ks/without-ns (keyword fn-name)))]
       (concat acc sigs)))
   '()
   fn-names))

(schema/defn ^:always-validate build-service-map :- ServiceMap
  "Given a map from service protocol function names (keywords) to service
  protocol functions, and a service instance, build up a map of partial
  functions closing over the service instance"
  [service-fn-map :- ServiceFnMap
   svc] ;; TODO this would be checked against Service, but it lives in services
  (into {}
        (map (fn [[fn-name fn-sym]]
               [fn-name (partial fn-sym svc)])
             service-fn-map)))

(schema/defn ^:always-validate build-output-schema :- {schema/Keyword (schema/pred (partial = IFn))}
  "Given a list of service protocol function names (keywords), build up the
  prismatic output schema for the service (a map from keywords to `IFn`)."
  [service-fn-names :- (schema/maybe [schema/Keyword])]
  (reduce (fn [acc fn-name] (assoc acc fn-name IFn))
          {}
          service-fn-names))

(schema/defn ^:always-validate build-fns-map! :- FnsMap
  "Given the list of fn forms from the service body, build up a map of
  service fn forms.  The keys of the map will be keyword representations
  of the function names, and the values will be the fn forms.  The final
  map will include default implementations of any lifecycle functions that
  aren't overridden in the service body.  Throws `IllegalArgumentException`
  if the fn forms do not match the protocol."
  [service-protocol-sym :- (schema/maybe Symbol)
   service-fn-names :- (schema/maybe (schema/pred coll?))
   lifecycle-fn-names :- [Symbol]
   fns :- [(schema/pred seq?)]]
  {:post [(= (ks/keyset %)
             (union (set (map (comp ks/without-ns keyword) service-fn-names))
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
                            (i18n/trs  "Incorrect macro usage: service functions must be defined the same as a call to `reify`, eg: `(my-service-fn [this other-args] ...)`"))))
                        (let [k    (keyword (first f))
                              cur  (acc k)]
                          (if cur
                            (assoc acc k (cons f cur))
                            (assoc acc k (list f)))))
                      {}
                      fns)
                     (add-default-lifecycle-fns lifecycle-fn-names))]
    (validate-provided-fns!
     service-protocol-sym
     (set (map (comp ks/without-ns keyword) service-fn-names))
     (difference (ks/keyset fns-map)
                 (set (map (comp ks/without-ns keyword) lifecycle-fn-names))))
    (when service-protocol-sym
      (validate-required-fns! service-protocol-sym service-fn-names fns-map))
    fns-map))

(schema/defn ^:always-validate get-service-id :- schema/Keyword
  "Generate service id based on service protocol symbol.  Returns the keyword of
  the symbol if the symbol is not nil; returns a keyword for a generated symbol
  otherwise."
  [service-protocol-sym :- (schema/maybe Symbol)]
  (ks/without-ns
   (if service-protocol-sym
     (keyword service-protocol-sym)
     (keyword (gensym "tk-service")))))

(schema/defn ^:always-validate get-service-fn-map :- ServiceFnMap
  "Get a map of service fns based on a protocol.  Keys will be keywords of the
  function names, values will be the protocol functions.  Returns
  an empty map if the protocol symbol is nil."
  [service-protocol-sym :- (schema/maybe Symbol)]
  (if service-protocol-sym
    (let [service-protocol-var  (resolve service-protocol-sym)
          service-protocol      (validate-protocol-sym!
                                 service-protocol-sym
                                 service-protocol-var)]
      (reduce (fn [acc fn-name]
                (assoc acc (keyword (name fn-name)) fn-name))
              {}
              (mapv var->symbol (keys (:method-builders service-protocol)))))
    {}))

(schema/defn ^:always-validate parse-service-forms! :- InternalServiceMap
  "Parse the forms provided to the `service` macro.  Return a map
  containing all of the data necessary to implement the macro:

  :service-protocol-sym - the service protocol symbol (or nil if there is no protocol)
  :service-id           - a unique identifier (keyword) for the service
  :service-fn-map       - a map of symbols for the names of the functions provided by the protocol
  :dependencies         - a vector (using fnk binding syntax) of deps or a map of :required and :optional deps.
  :fns-map              - a map of all of the fn definition forms in the service"
  [lifecycle-fn-names :- [(schema/pred symbol?)]
   forms :- (schema/pred seq?)]
  (let [service-sym (:service-symbol (first forms))
        service-sym (if (symbol? service-sym) service-sym nil)
        forms (if (nil? service-sym) forms (rest forms))
        {:keys [service-protocol-sym dependencies fns]} (find-prot-and-deps-forms! forms)
        service-id        (get-service-id service-protocol-sym)
        service-fn-map    (get-service-fn-map service-protocol-sym)

        fns-map           (build-fns-map!
                           service-protocol-sym
                           (vals service-fn-map)
                           lifecycle-fn-names
                           fns)]

    {:service-sym           service-sym
     :service-protocol-sym  service-protocol-sym
     :service-id            service-id
     :service-fn-map        service-fn-map
     :dependencies          dependencies
     :fns-map               fns-map}))
