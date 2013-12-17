(ns puppetlabs.trapperkeeper.services
  (:require [plumbing.core :refer [fnk]]
            [puppetlabs.trapperkeeper.app])
  (:import (puppetlabs.trapperkeeper.app TrapperKeeperApp)))

(defn- io->fnk-binding-form
  "Converts a service's input-output map into a binding-form suitable for
  passing to a fnk. The binding-form defines the fnk's expected input and
  output values, and is required to satisfy graph compilation.

  This function is necessary in order to allow for the defservice macro to
  support arbitrary code in the body. A fnk will attempt to determine what
  its output-schema is, but will only work if a map is immediately returned
  from the body. When a map is not immediately returned (i.e. a `let` block
  around the map), the output-schema must be explicitly provided in the fnk
  metadata."
  [io-map]
  (let [to-output-schema  (fn [provides]
                            (reduce (fn [m p] (assoc m (keyword p) true))
                                    {}
                                    provides))
        output-schema     (to-output-schema (:provides io-map))]
    ;; Add an output-schema entry to the depends vector's metadata map
    (vary-meta (:depends io-map) assoc :output-schema output-schema)))

(defn- validate-io-map!
  "Validates a service's io-map contains the required :depends & :provides keys,
  otherwise an exception is thrown."
  [io-map]
  {:pre [(map? io-map)]}
  (when-not (contains? io-map :depends)
    (throw (IllegalArgumentException. ":depends is required in service definition")))
  (when-not (contains? io-map :provides)
    (throw (IllegalArgumentException. ":provides is required in service definition"))))

(defmacro service
  "Define a service that may depend on other services, and provides functions
  for other services to depend on. This macro is intended to be used inline
  rather than at the top-level (see `defservice` for that).

  Defining a service requires a:
    * service name keyword
    * input-output map in the form: {:depends [...] :provides [...]}
    * a body of code that returns a map of functions the service provides.
      The keys of the map must match the values of the :provides vector.

  Example:

    (service :logging-service
      {:depends  []
       :provides [log]}
      {:log (fn [msg] (println msg))})"
  [svc-name io-map & body]
  (validate-io-map! io-map)
  (let [binding-form (io->fnk-binding-form io-map)]
    `(fn []
       {~svc-name
        (fnk
          ~binding-form
          ~@body)})))

(defmacro defservice
  "Define a service that may depend on other services, and provides functions
  for other services to depend on. Defining a service requires a:
    * service name
    * optional documentation string
    * input-output map in the form: {:depends [...] :provides [...]}
    * a body of code that returns a map of functions the service provides.
      The keys of the map must match the values of the :provides vector.

  Examples:

    (defservice logging-service
      {:depends  []
       :provides [debug info warn]}
      {:debug (partial println \"DEBUG:\")
       :info  (partial println \"INFO:\")
       :warn  (partial println \"WARN:\")})

    (defservice datastore-service
      \"Store key-value pairs.\"
      {:depends  [[:logging-service debug]]
       :provides [get put]}
      (let [log       (partial debug \"[datastore]\")
            datastore (atom {})]
        {:get (fn [key]       (log \"Getting...\") (get @datastore key))
         :put (fn [key value] (log \"Putting...\") (swap! datastore assoc key value))}))"
  [svc-name & forms]
  (let [[svc-doc io-map body] (if (string? (first forms))
                                [(first forms) (second forms) (nthrest forms 2)]
                                ["" (first forms) (rest forms)])]
    `(def ~svc-name
       ~svc-doc
       (service ~(keyword svc-name) ~io-map ~@body))))

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
  (get-in (:graph-instance app) [service service-fn]))
