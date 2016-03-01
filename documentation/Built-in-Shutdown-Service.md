# Trapperkeeper's Built-in Shutdown Service

The shutdown service is built-in to Trapperkeeper and, like the [configuration service](Built-in-Configuration-Service.md), is always loaded.  It has two main responsibilities:

* Listen for a shutdown signal to the process, and initiate shutdown of the application if one is received (via CTRL-C or TERM signal)
* Provide functions that can be used by other services to initiate a shutdown (either because of a normal application termination condition, or in the event of a fatal error)

## Shutdown Hooks

A service may implement the `stop` function from the `Lifecycle` protocol.  If so, this function will be called during application shutdown.  The shutdown hook for any given service is guaranteed to be called *before* the shutdown hook for any of the services that it depends on.

For example:

```clj
(defn bar-shutdown
   []
   (log/info "bar-service shutting down!"))

(defservice bar-service
   [[:FooService foo]]
   ;; service initialization code
   (init [this context]
     (log/info "bar-service initializing.")
     context)

   ;; shutdown code
   (stop [this context]
      (bar-shutdown.md)
      context))
```

Given this service definition, the `bar-shutdown` function would be called during shutdown of the Trapperkeeper container (during both a normal shutdown or an error shutdown).  Because `bar-service` has a dependency on `foo-service`, Trapperkeeper would also guarantee that the `bar-shutdown` is called *prior to* the `stop` function for the `foo-service` (assuming `foo-service` provides one).

## Provided Shutdown Functions

The shutdown service provides two functions that can be injected into other services: `request-shutdown` and `shutdown-on-error`.  Here's the protocol:

```clj
(defprotocol ShutdownService
  (request-shutdown [this] "Asynchronously trigger normal shutdown")
  (shutdown-on-error [this service-id f] [this service-id f on-error]
    "Higher-order function to execute application logic and trigger shutdown in
    the event of an exception"))
```

To use them, you may simply specify a dependency on them:

```clj
(defservice baz-service
   [[:ShutdownService request-shutdown shutdown-on-error]]
   ;; ...
   )
```

### `request-shutdown`

`request-shutdown` is a no-arg function that will simply cause Trapperkeeper to initiate a normal shutdown of the application container (which will, in turn, cause all registered shutdown hooks to be called).  It is asynchronous.

### `shutdown-on-error`

`shutdown-on-error` is a higher-order function that can be used as a wrapper around some logic in your services; its functionality is simple:

```clj
(try
  ; execute the given function
  (catch Throwable t
    ; initiate Trapperkeeper's shutdown logic
```
This has two main use-cases:
* "worker" / background threads that your service may launch
* a section of code that needs to execute in a service function, in which any error is so problematic that the entire application should shut down

`shutdown-on-error` accepts either two or three arguments: `[service-id f]` or `[service-id f on-error-fn]`.

`service-id` is the id of your service; you can retrieve this via `(service-id this)` inside of any of your service function definitions.

`f` is a function containing whatever application logic you desire; this is the function that will be wrapped in `try/catch`.  `on-error-fn` is an optional callback function that you can provide, which will be executed during error shutdown *if* an unhandled exception occurs during the execution of `f`.  `on-error-fn` should take a single argument: `context`, which is the service context map (the same map that is used in the lifecycle functions).

Here's an example:

```clj
(defn my-work-fn
   []
   ;; do some work
   (Thread/sleep 10000)
   ;; uh-oh!  An unhandled exception!
   (throw (IllegalStateException. "egads!")))

(defn my-error-cleanup-fn
   [context]
   (log/info "Something terrible happened!  Foo: " (context :foo))
   (log/info "Performing shutdown logic that should only happen on a fatal error."))

(defn my-normal-shutdown-fn
   []
   (log/info "Performing normal shutdown logic."))

(defservice yet-another-service
   [[:ShutdownService shutdown-on-error]]
   (init [this context]
      (assoc context
         :worker-thread
         (future (shutdown-on-error (service-id this) my-work-fn my-error-cleanup-fn))))

   (stop [this context]
      (my-normal-shutdown-fn.md)
      context))
```

In this scenario, the application would run for 10 seconds, and then the fatal exception would be thrown.  Trapperkeeper would then call `my-error-cleanup-fn`, and then attempt to call all of the normal shutdown hooks in the correct order (including `my-normal-shutdown-fn`).
