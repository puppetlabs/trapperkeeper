# Referencing Services

One of the most important features of Trapperkeeper is the ability to specify dependencies between services, and, thus, to reference functions provided by one service from functions in another service.  Trapperkeeper actually exposes several different ways to reference such functions, since the use cases may vary a great deal depending on the particular services involved.

## Individual Functions

In the simplest case, you may just want to grab a direct reference to one or more individual functions from another service.  That can be accomplished like this:

```clj
(defservice foo-service
  [[:BarService bar-fn]
   [:BazService baz-fn]]
  (init [this context]
    (bar-fn)
    (baz-fn)
    context))
```

This form expresses a dependency on two other services; one implementing the `BarService` protocol, and one implementing the `BazService` protocol.  It gives us a direct reference to the functions `bar-fn` and `baz-fn`.  You can call them as normal functions, without worrying about protocols any further.

## A Map of Functions

If you want to get simple references to plain-old functions from a service (again, without worrying about the protocols), but you don't want to have to list them all out explicitly in the binding form, you can do this:

```clj
(defservice foo-service
  [BarService BazService]
  (init [this context]
    ((:bar-fn BarService))
    ((:baz-fn BazService))
    context))
```

With this syntax, what you get access to are two local vars `BarService` and `BazService`, the value of each of which is a map.  The map keys are all keyword versions of the function names for all of the functions provided by the service protocol, and the values are the plain-old functions that you can just call directly.

## Plumatic Graph Binding Form

Both of the cases above are actually just specific examples of forms supported by the underlying Plumatic Graph library that we are using to manage dependencies.  If you're interested, the plumatic library offers some other ways to specify the binding forms and access your dependencies.  For more info, see the  [fnk binding syntax from the Plumatic plumbing library](https://github.com/plumatic/plumbing/tree/master/src/plumbing/fnk#fnk-syntax).

## Via Service Protocol

In some cases you may actually prefer to get a reference to an object that satisfies the service protocol.  This way, you can pass the object around and use the actual clojure protocol to reference the functions provided by a service.  To achieve this, you use the `get-service` function from the main `Service` protocol.  Here's how this might look:

```clj
(ns bar.service)

(defprotocol BarService
   (bar-fn [this]))

...

(ns foo.service
   (:require [bar.service :as bar]))

(defservice foo-service
   ;; This dependency is only here to enforce that the BarService gets loaded
   ;; before this service does; we won't need to refer to the `BarService` var
   ;; anywhere in this service definition.
   [BarService]
   (init [this context]
      (let [bar-service (get-service this :BarService)]
         (bar/bar-fn bar-service))
      context))
```
