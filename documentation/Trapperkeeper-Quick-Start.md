# Trapperkeeper Quick Start

## Lein Template

A Leiningen template is available that shows a suggested project structure:

    lein new trapperkeeper my.namespace/myproject

Once you've created a project from the template, you can run it via the lein alias:

    lein tk

Note that the template is not intended to suggest a specific namespace organization; it's just intended to show you how to write a service, a web service, and tests for each.

## Hello World

Here's a "hello world" example for getting started with Trapperkeeper.

First, you need to define one or more services:

```clj
(ns hello
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]))

;; A protocol that defines what functions our service will provide
(defprotocol HelloService
  (hello [this])

(defservice hello-service
  HelloService
  ;; dependencies: none for this service
  []
  ;; optional lifecycle functions that we can implement if we choose
  (init [this context]
      (println "Hello service initializing!")
      context)
  ;; implement our protocol functions
  (hello [this] (println "Hello there!")))

(defservice hello-consumer-service
  ;; no protocol required since this service doesn't export any functions.
  ;; express a dependency on the `hello` function from the `HelloService`.
  [[:HelloService hello]]
  (init [this context]
    (println "Hello consumer initializing; hello service says:")
    ;; call the function from the `hello-service`!
    (hello)
    context))
```

Then, you need to define a Trapperkeeper bootstrap configuration file, which simply lists the services that you want to load at startup.  This file should be named `bootstrap.cfg` and should be located at the root of your classpath (a good spot for it would be in your `resources` directory).

```clj
hello/hello-consumer-service
hello/hello-service
```

Lastly, set Trapperkeeper to be your `:main` in your Leiningen project file:

```clj
:main puppetlabs.trapperkeeper.main
```

And now you should be able to run the app via `lein run --config ...`.  This example doesn't do much; for a more interesting example that shows how you can use Trapperkeeper to create a web application, check out the [Example Web Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/tree/master/examples/ring_app) included in the Trapperkeeper webserver service project.  To get started defining your own services in Trapperkeeper, head to the [Defining Services](Defining-Services) page.
