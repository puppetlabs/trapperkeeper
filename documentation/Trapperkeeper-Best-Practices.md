# Trapperkeeper Best Practices

This page provides some general guidelines for writing Trapperkeeper services.

## To Trapperkeeper Or Not To Trapperkeeper

Trapperkeeper gives us a lot of flexibility on how we decide to package and deploy applications and services.  When should you use it?  The easiest rule of thumb is: if it's possible to expose your code as a simple library with no dependencies on Trapperkeeper, it's highly preferable to go that route.  Here are some things that might be reasonable indicators that you should consider exposing your code via a Trapperkeeper service:

* You're writing a clojure web service and there is a greater-than-zero percent chance that you will eventually want to be able to run it inside of the same embedded web server instance as another web service.
* Your code initializes some long-lived, stateful resource that needs to be used by other code, and that other code might not want/need to be responsible for explicitly managing the lifecycle of your resource
* Your code has a need for a managed lifecycle; initialization / startup, shutdown / cleanup
* Your code has a dependency on some other code that has a managed lifecycle
* Your code requires external configuration that you would like to make consistent with other puppetlabs / Trapperkeeper applications

## Separating Logic From Service Definitions

In general, it's a good idea to keep the code that implements your business logic completely separated from the Trapperkeeper service binding.  This makes it much easier to test your functions as functions, without the need to boot up the whole framework.  It also makes your code more re-usable and portable.  Here's a more concrete example:

*DON'T DO THIS:*

```clj
(defprotocol CalculatorService
   (add [this x y]))

(defservice calculator-service
   CalculatorService
   []
   (add [this x y] (+ x y)))
```

This is better:

```clj
(ns calculator.core)

(defn add [x y] (+ x y))
```
```clj
(ns calculator.service
   (:require calculator.core :as core))

(defprotocol CalculatorService
   (add [this x y]))

(defservice calculator-service
   CalculatorService
   []
   (add [this x y] (core/add x y)))
```

This way, you can test `calculator.core` directly, and re-use the functions it provides in other places without having to worry about Trapperkeeper.

## On Lifecycles

Trapperkeeper provides three lifecycle functions: init, start, and stop.  Hopefully "stop" is pretty obvious.  We've had some questions, though, about what the difference is between "init" and "start".  Trapperkeeper doesn't impose a hard-and-fast rule that you must follow for how you use these, but here are some data points:

* The 'init' function of any service that you depend on will always be called before your 'init', and before any 'start'.  The 'start' function of any service that you depend on will always be called before your 'start'.
* Trapperkeeper itself doesn't impose any semantics about what kinds of things you should do in each of those lifecycle phases.  It's more about giving services the flexibility to establish a contract with other services.  For example, a webserver service may specify that it only accepts the registration of web handlers during the 'init' phase, and that no new handlers can be added after it has completed its 'start' phase.  (This is just a theoretical example; this restriction isn't actually true for our current jetty implementations.)
* The lifecycles are relatively new; as people start to use these lifecycles a bit more, we may end up shaking out a more concrete best-practice pattern.  It's also possible we might end up introducing another phase or two to give more granularity... for now, we wanted to try to keep it fairly simple and flexible, and get a handle on what kinds of use cases people end up having for it.

## Testing Services

As we mentioned before, it's better to separate your business logic from your service definitions as much as possible, so that you can test your business logic functions directly.  Thus, the vast majority of your tests should not need to involve Trapperkeeper at all.  However, you probably will want to have a small handful of tests that do boot up a full Trapperkeeper app, so that you can verify that your dependencies work as expected, etc.

When writing tests that boot a Trapperkeeper app, the best way to do it is to use the helper testutils macros that we describe in the [testutils documentation](Test-Utils.md).  They will take care of things like making sure the application is shut down cleanly after the test, and will generally just make your life easier.
