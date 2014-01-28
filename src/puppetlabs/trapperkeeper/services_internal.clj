(ns puppetlabs.trapperkeeper.services-internal
  (:require [clojure.walk :refer [postwalk]]
            [clojure.set :refer [difference union intersection]]
            [plumbing.core :refer [fnk]]
            [puppetlabs.kitchensink.core :refer [keyset]]))


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

(defn validate-fn-forms!
  "Validate that all of the fn forms in the service body appear to be
  valid fn definitions.  Throws `IllegalArgumentException` otherwise."
  [protocol-sym deps fns]
  {:pre [((some-fn nil? symbol?) protocol-sym)
         (vector? deps)
         (seq? fns)]
   :post [(vector? %)]}
  (if (every? seq? fns)
    [protocol-sym deps fns]
    (throw (IllegalArgumentException.
             (format
               "Invalid service definition; expected function definitions following dependency list, invalid value: '\"hi\"'"
               (pr-str (first (filter #(not (seq? %)) fns))))))))

(defn validate-deps-form!
  "Validate that the service body has a valid dependency specification.
  Throws `IllegalArgumentException` otherwise."
  [protocol-sym forms]
  {:pre [((some-fn nil? symbol?) protocol-sym)
         (seq? forms)]
   :post [(vector? %)]}
  (let [f (first forms)]
    (if (vector? f)
      (validate-fn-forms! protocol-sym f (rest forms))
      (throw (IllegalArgumentException.
               (format
                 "Invalid service definition; expected dependency list following protocol, found: '%s'"
                 (pr-str f)))))))

(defn find-prot-and-deps-forms!
  "Given the forms passed to the service macro, find the service protocol
  (if one is provided), the dependency list, and the function definitions.
  Throws `IllegalArgumentException` if the forms do not represent a valid service.
  Returns a vector containing the protocol, dependency list, and fn forms."
  [forms]
  {:pre [(seq? forms)]
   :post [(vector? %)
          (= 3 (count %))
          ((some-fn nil? symbol?) (first %))
          (vector? (second %))
          (seq? (nth % 2))]}
  (let [f (first forms)]
    (cond
      (symbol? f) (validate-deps-form! f (rest forms))
      (vector? f) (validate-deps-form! nil forms)
      :else (throw (IllegalArgumentException.
                     (format
                       "Invalid service definition; first form must be protocol or dependency list; found '%s'"
                       (pr-str f)))))))

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
  (let [collisions (intersection (set service-fn-names)
                                 (set lifecycle-fn-names))]
    (if-not (empty? collisions)
      (throw (IllegalArgumentException.
               (format "Service protocol '%s' includes function named '%s', which conflicts with lifecycle function by same name"
                       (name service-protocol-sym)
                       (name (first collisions))))))))

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
  (let [extras (difference provided-fns ^{:fs 3} service-fns)]
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
         (coll? required-fn-names)
         (every? symbol? required-fn-names)
         (symbol? protocol-sym)]}
  (doseq [fn-name required-fn-names]
    (if-not (contains? fns-map (keyword fn-name))
      (throw (IllegalArgumentException.
               (format "Service does not define function '%s', which is required by protocol '%s'"
                       fn-name (name protocol-sym)))))))

(defn add-default-lifecycle-fn
  "Given a map of fns defined by a service, and the name of a lifecycle function,
  check to see if the fns map includes an implementation of the lifecycle function.
  If not, add a default implementation."
  [fns-map fn-name]
  {:pre [(fns-map? fns-map)
         (symbol? fn-name)]
   :post [(fns-map? %)
          (= (keyset %) (conj (keyset fns-map) (keyword fn-name)))]}
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
          (= (keyset %)
             (union (keyset fns-map)
                    (set (map keyword lifecycle-fn-names))))]}
  (reduce add-default-lifecycle-fn fns-map lifecycle-fn-names))

(defn build-fns-map!
  "Given the list of fn forms from the service body, build up a map of
  service fn forms.  The keys of the map will be keyword representations
  of the function names, and the values will be the fn forms.  The final
  map will include default implementations of any lifecycle functions that
  aren't overridden in the service body.  Throws `IllegalArgumentException`
  if the fn forms do not match the protocol."
  [service-protocol-sym service-fn-names lifecycle-fn-names fns]
  {:pre [((some-fn nil? symbol?) service-protocol-sym)
         (coll? service-fn-names)
         (every? symbol? service-fn-names)
         (coll? lifecycle-fn-names)
         (every? symbol? lifecycle-fn-names)
         (every? seq? fns)]
   :post [(fns-map? %)
          (= (keyset %)
             (union (set (map keyword service-fn-names))
                    (set (map keyword lifecycle-fn-names))))]}

  (when service-protocol-sym
    (validate-protocol-fn-names! service-protocol-sym service-fn-names lifecycle-fn-names))

  (let [fns-map (->> (reduce
                       (fn [acc f]
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
      (set (map keyword service-fn-names))
      (difference (keyset fns-map)
                  (set (map keyword lifecycle-fn-names))))
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
  (if service-protocol-sym
    (keyword service-protocol-sym)
    (keyword (gensym "tk-service"))))

(defn get-service-fn-names
  "Get a list of service fn names based on a protocol.  Returns
  an empty list if the protocol symbol is nil."
  [service-protocol-sym]
  {:pre [((some-fn nil? symbol?) service-protocol-sym)]
   :post [(coll? %)
          (every? symbol? %)]}
  (let [service-protocol  (if service-protocol-sym
                            (validate-protocol-sym!
                              service-protocol-sym
                              (resolve service-protocol-sym)))]
    (if service-protocol
      (map :name (vals (:sigs service-protocol)))
      [])))

(defn parse-service-forms!
  "Parse the forms provided to the `service` macro.  Return a map
  containing all of the data necessary to implement the macro:

  :service-protocol-sym - the service protocol symbol (or nil if there is no protocol)
  :service-id           - a unique identifier (keyword) for the service
  :service-fn-names     - a list of symbols for the names of the functions provided by the protocol
  :dependencies         - a vector specifying the dependencies of the service
  :fns-map              - a map of all of the fn definition forms in the service"
  [lifecycle-fn-names forms]
  {:pre [(every? symbol? lifecycle-fn-names)
         (seq? forms)]
   :post [(map? %)
          (= #{:service-protocol-sym :service-id :service-fn-names
               :dependencies :fns-map} (keyset %))]}
  (let [[service-protocol-sym dependencies fns]
                          (find-prot-and-deps-forms! forms)
        service-id        (get-service-id service-protocol-sym)
        service-fn-names  (get-service-fn-names service-protocol-sym)

        fns-map           (build-fns-map!
                            service-protocol-sym
                            service-fn-names
                            lifecycle-fn-names
                            fns)]

    {:service-protocol-sym  service-protocol-sym
     :service-id            service-id
     :service-fn-names      service-fn-names
     :dependencies          dependencies
     :fns-map               fns-map}))

(defn postwalk-with-pred
  "Convenience wrapper for clojure.walk/postwalk.  Given two functions `matches?`
  and `replace` walks form `form`.  For each node, if `matches?` returns true,
  then replaces the node with the result of `replace`."
  [matches? replace form]
  {:pre [(ifn? matches?)
         (ifn? replace)]}
  (postwalk (fn [x]
              (if-not (matches? x)
                x
                (replace x)))
    form))

(defn is-protocol-fn-call?
  "Given a set of function names, a symbol representing the 'this' object of
  a protocol function signature, and a form: return true if the form represents
  a call to one of the protocol functions.  (This is for use in macros that are
  transforming protocol function definition code.)"
  [fns this form]
  {:pre [(set? fns)
         (every? symbol? fns)
         (symbol? this)]}
  (and (seq? form)
       (> (count form) 1)
       (= this (second form))
       (contains? fns (first form))))

(defn replace-fn-calls
  "Given a set of function names, a symbol representing the 'this' object of
  a protocol function signature, and a form: find all of the calls to the protocol
  functions anywhere in the form and replace them with calls to the prismatic
  graph functions.  (This is for use in macros that are transforming protocol
  function definition code.)

  Returns a tuple whose first element is a set containing the names of all of
  the functions that were found in the form, and whose second element is
  the modified form."
  [fns this form]
  {:pre [(set? fns)
         (every? symbol? fns)
         (symbol? this)]
   :post [(vector? %)
          (set? (first %))
          (every? symbol? (first %))]}
  ;; in practice, all our 'replace' function really needs to do is to
  ;; remove the 'this' argument.  The function signatures in the graph are
  ;; identical to the ones in the protocol, except without the 'this' arg.
  (let [replace     (fn [form] (cons (first form) (nthrest form 2)))
        ;; we need an atom to accumulate the matches that we find, because
        ;; clojure.walk doesn't provide any signatures that support an accumulator.
        ;; could eventually look into replacing this with an implementation based
        ;; off of a zipper, but that looked like a lot of work for now.
        acc         (atom #{})
        accumulate  (fn [form] (swap! acc conj (first form)))
        result      (postwalk-with-pred
                      (partial is-protocol-fn-call? fns this)
                      (fn [form] (accumulate form) (replace form))
                      form)]
    [@acc result]))

(defn protocol-fn->graph-fn
  "Given a list of fn names provided by a service, and a list of fn forms for a
  (potentially multi-arity) fn from the service macro, create a fn form that is
  suitable for use in a prismatic graph.  Returns a map containing the following
  keys:

  :deps - a list of fns from the service that this fn depends on
  :f    - the modified fn form, suitable for use in the graph"
  [fn-names sigs]
  {:pre [(every? symbol? fn-names)
         (seq? sigs)
         (every? seq? sigs)]
   :post [(map? %)
          (= #{:deps :f} (keyset %))
          (coll? (:deps %))
          (seq? (:f %))
          (= 'fn (first (:f %)))]}
  (let [bodies (for [sig sigs]
                  ;; first we destructure the function form into its various parts
                  (let [[_ [this & fn-args] & fn-body] sig
                        ;; now we need to transform all calls to `service-context` from
                        ;; protocol form to prismatic form.  we don't need to track this as
                        ;; a dependency because it will be provided by the app.
                        [_ fn-body] (replace-fn-calls #{'service-context 'service-id} this fn-body)
                        ;; transform all the functions from the service protocol, and keep
                        ;; a list of the dependencies so that prismatic can inject them
                        [deps fn-body] (replace-fn-calls (set fn-names) this fn-body)]
                    {:deps deps :sig (cons (vec fn-args) fn-body)}))]
    {:deps (->> bodies (map :deps) (apply concat) (distinct))
     :f    (cons 'fn (map :sig bodies))}))

(defn add-prismatic-service-fnk
  "Given the name of a fn from a service protocol, convert the raw fn form provided
  to the macro into a fn form suitable for use in a prismatic graph, wrap it in a
  prismatic fnk, and add it to the fnk accumulator map.  Returns the updated
  accumulator map."
  [fn-names fns-map fnk-acc fn-name]
  {:pre [(every? symbol? fn-names)
         (fns-map? fns-map)
         (map? fnk-acc)
         (every? keyword? (keys fnk-acc))
         (every? (fn [v] (= 'plumbing.core/fnk (first v))) (vals fnk-acc))
         (keyword? fn-name)]
   :post [(map? %)
          (every? keyword? (keys %))
          (every? seq? (vals %))
          (every? (fn [v] (= 'plumbing.core/fnk (first v))) (vals %))]}
  (let [{:keys [deps f]} (protocol-fn->graph-fn
                           fn-names
                           (fns-map fn-name))]
    (assoc fnk-acc fn-name
      (list 'plumbing.core/fnk (vec deps) f))))

(defn prismatic-service-map
  "Given a list of fn names and a map of the original fn forms provided to the
  service macro, return a map of fnks suitable for use in a prismatic graph."
  [fn-names fns-map]
  {:pre [(every? symbol? fn-names)
         (fns-map? fns-map)]
   :post [(map? %)
          (every? keyword? (keys %))
          (every? seq? (vals %))
          (every? (fn [v] (= 'plumbing.core/fnk (first v))) (vals %))
          (= (set (map keyword fn-names))
             (keyset fns-map))]}
  (reduce
    (partial add-prismatic-service-fnk fn-names fns-map)
    {}
    (map keyword fn-names)))

(defn protocol-fn
  "Given a form containing the definition of a service function, return a fn form
  that simply proxies to the corresponding function in the prismatic graph (`service-fns`)."
  [sig]
  {:pre [(fn-sig? sig)]}
  (let [[fn-name fn-args & _] sig]
    (list
      fn-name
      fn-args
      (cons
        (list 'service-fns (keyword fn-name))
        (rest fn-args)))))

(defn protocol-fns
  "Given a list of fn names and a map containing the original fn forms provided to
  the service macro, return a list of modified fn forms that simply proxy to the
  functions in the prismatic graph."
  [fn-names fns-map]
  {:pre [(every? symbol? fn-names)
         (fns-map? fns-map)]
   :post [(seq? %)
          (every? seq? %)]}

  (reduce
    (fn [acc fn-name]
      (let [sigs (fns-map (keyword fn-name))]
        (concat acc (for [sig sigs] (protocol-fn sig)))))
    '()
    fn-names))

