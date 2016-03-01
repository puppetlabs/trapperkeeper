# Defining Services

Trapperkeeper provides two constructs for defining services: `defservice` and `service`.  As you might expect, `defservice` defines a service as a var in your namespace, and `service` allows you to create one inline and assign it to a variable in a let block or other location.  Here's how they work:

## `defservice`

`defservice` takes the following arguments:

* a service name
* an optional doc string
* an optional service protocol; only required if your service exports functions that can be used by other services
* a dependency list indicating other services/functions that this service requires
* a series of function implementations.  This must include all of the functions in the protocol if one is specified, and may also optionally provide override implementations for the built-in service `Lifecycle` functions.

### Service Lifecycle

The service `Lifecycle` protocol looks like this:

```clj
(defprotocol Lifecycle
  (init [this context])
  (start [this context])
  (stop [this context]))
```

(This may look familiar; we chose to use the same function names as some of the existing lifecycle protocols.  Ultimately we'd like to just use one of those protocols directly, but for now our needs are different enough to warrant avoiding the introduction of a dependency on an existing project.)

All service lifecycle functions are passed a service `context` map, which may be used to store any service-specific state (e.g., a database connection pool or some other object that you need to reference in subsequent functions.)  Services may define these functions, `assoc` data into the map as needed, and then return the updated context map.  The updated context map will be maintained by the framework and passed to subsequent lifecycle functions for the service.

The default implementation of the lifecycle functions is to simply return the service context map unmodified; if you don't need to implement a particular lifecycle function for your service, you can simply omit it and the default will be used.

Trapperkeeper will call the lifecycle functions in order based on the dependency list of the services; in other words, if your service has a dependency on service `Foo`, you are guaranteed that `Foo`'s `init` function will be called prior to yours, and that your `stop` function will be called prior to `Foo`'s.

### Example Service

Let's look at a concrete example:

```clj
;; This is the list of functions that the `FooService` must implement, and which
;; are available to other services who have a dependency on `FooService`.
(defprotocol FooService
  (foo1 [this x])
  (foo2 [this])
  (foo3 [this x]))

(defservice foo-service
   ;; docstring (optional.md)
   "A service that foos."

   ;; now we specify the (optional.md) protocol that this service satisfies:
   FooService

   ;; the :depends value should be a vector of vectors.  Each of the inner vectors
   ;; should begin with a keyword that matches the protocol name of another service,
   ;; which may be followed by any number of symbols.  Each symbol is the name of a
   ;; function that is provided by that service.  Trapperkeeper will fail fast at
   ;; startup if any of the specified dependency services do not exist, *or* if they
   ;; do not provide all of the functions specified in your vector.  (Note that
   ;; the syntax used here is actually just the
   ;; [fnk binding syntax from the Plumatic plumbing library](https://github.com/plumatic/plumbing/tree/master/src/plumbing/fnk#fnk-syntax),
   ;; so you can technically use any form that is compatible with that.)
   [[:SomeService function1 function2]
    [:AnotherService function3 function4]]

   ;; After your dependencies list comes the function implementations.
   ;; You must implement all of the protocol functions (if a protocol is
   ;; specified), and you may also override any `Lifecycle` functions that
   ;; you choose.  We'll start by implementing the `init` function from
   ;; the `Lifecycle`:
   (init [this context]
      ;; do some initialization
      ;; ...
      ;; now return the service context map; we can update it to include
      ;; some state if we like.  Note that we can use the functions that
      ;; were specified in our dependency list here:
      (assoc context :foo (str "Some interesting state:" (function1.md)))

   ;; We could optionally also override the `start` and `stop` lifecycle
   ;; functions, but we won't for this example.

   ;; Now we'll define our service function implementations.  Again, we are
   ;; free to use the imported functions from the other services here:
   (foo1 [this x] ((comp function2 function3) x))
   (foo2 [this] (println "Function4 returns" (function4.md)))

   ;; We can also access the service context that we updated during the
   ;; lifecycle functions, by using the `service-context` function from
   ;; the `Service` protocol:
   (foo3 [this x]
     (let [context (service-context this)]
       (format "x + :foo is: '%s'" (str x (:foo context))))))
```

After this `defservice` statement, you will have a var named `foo-service` in your namespace that contains the service.  You can reference this from a Trapperkeeper bootstrap configuration file to include that service in your app, and once you've done that your new service can be referenced as a dependency (`{:depends [[:FooService ...`) by other services.

### Multi-arity Protocol Functions

Clojure's protocols allow you to define multi-arity functions:

```clj
(defprotocol MultiArityService
   (foo [this x] [this x y]))
```

Trapperkeeper services can use the syntax from clojure's `reify` to implement these multi-arity functions:

```clj
(defservice my-service
   MultiArityService
   []
   (foo [this x] x)
   (foo [this x y] (+ x y)))
```

## `service`

`service` works very similarly to `defservice`, but it doesn't define a var in your namespace; it simply returns the service instance.  Here are some examples (with and without protocols):

```clj
(service
   []
   (init [this context]
     (println "Starting anonymous service!")
     context))

(defprotocol AnotherService
   (foo [this]))
```

## Optional Services

_Introduced in Trapperkeeper 1.2.0_

When defining a service, it is possible to mark certain other services your service depends on as being optional. This is useful, for example, when composing your service against services that you might not need during development or for certain deployment scenarios. You can write the same code whether or not an optional service has been included in your bootstrap.cfg or not.

To mark a dependency as optional, you use a different form to specify your dependencies:

```clj
(defprotocol HaikuService
  (get-haiku [this] "return a lovely haiku"))
(defprotocol SonnetService
  (get-sonnet [this] "return a lovely sonnet"))

;; ... snip the definitions of HaikuService and SonnetService ...

(defservice poetry-service
  PoetryService
  {:required [HaikuService]
   :optional [SonnetService]}
  (haiku [this]
    (get-haiku HaikuService))
  (sonnet [this]
    (if-let [sonnet-svc (tk-svc/maybe-get-service this :SonnetService)]
      (get-sonnet sonnet-svc)
      "insert moving sonnet here"))
```

In the above example, we use a map of the form `{:required [...] :optional [...]}` to split up our required and optional dependencies. When we run this service in TK, our code will call `(get-sonnet.md)` if an implementation of `SonnetService` has been included in the `bootstrap.cfg`. Otherwise, we'll return the placeholder string `"insert moving sonnet here"`.

**Warning** Because of a [limitation](https://github.com/plumatic/plumbing/issues/114) in Plumatic Schema, you can't use the destructuring `[:SonnetService get-sonnet]` syntax when declaring optional dependencies.

The `Service` protocol has two helpers to make it easier to work with optional dependencies:

* `(maybe-get-service [this service-id])` which takes a keyword service ID and returns the service, if included, or nil
* `(service-included? [this service-id])` which takes a keyword service ID and returns true or false based on its inclusion.

These helpers live alongside the other service helpers like `get-service` in `puppetlabs.trapperkeeper.services`.

## Referencing Services

To learn how to refer to services in the rest of your application, head over to the [Referencing Services](Referencing-Services.md) page.
