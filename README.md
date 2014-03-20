[![Build Status](https://travis-ci.org/puppetlabs/trapperkeeper.png?branch=master)](https://travis-ci.org/puppetlabs/trapperkeeper)

# Trapperkeeper

## Installation

Add the following dependency to your `project.clj` file:

    [puppetlabs/trapperkeeper "0.3.4"]

## Overview

Trapperkeeper is a clojure framework for hosting long-running applications and
services.  You can think of it as a "binder", of sorts--for ring applications
and other modular bits of clojure code.

It ties together a few nice patterns we've come across in the clojure
community:

* Stuart Sierra's ["reloaded" workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded)
* Component lifecycles (["Component"](https://github.com/stuartsierra/component), ["jig"](https://github.com/juxt/jig#components))
* [Composable services](http://blog.getprismatic.com/blog/2013/2/1/graph-abstractions-for-structured-computation) (based on the excellent [Prismatic graph library](https://github.com/Prismatic/plumbing))

We also had a few other needs that Trapperkeeper addresses (some of these arise
because of the fact that we at Puppet Labs are shipping on-premise software, rather
than SaaS.  The framework is a shipping part of the application, in addition to
providing useful features for development):

* Well-defined service interfaces (using clojure protocols)
* Ability to turn services on and off via configuration after deploy
* Ability to swap service implementations via configuration after deploy
* Ability to load multiple web apps (usually Ring) into a single webserver
* Unified initialization of logging and configuration so services don't have to
  concern themselves with the implementation details
* Super-simple configuration syntax

A "service" in Trapperkeeper is represented as simply a map of clojure functions.
Each service can advertise the functions that it provides via a protocol, as well
as list other services that it has a dependency on.  You then configure
Trapperkeeper with a list of services to run and launch it.  At startup, it
validates that all of the dependencies are met and fails fast if they are not.
If they are, then it injects the dependency functions into each service and
starts them all up in the correct order.

Trapperkeeper provides a few built-in services such as a configuration service,
a shutdown service, and an nREPL service.  Other services (such as a web server
service) are available and ready to use, but don't ship with the base framework.
Your custom services can specify dependencies on these and leverage the functions
that they provide.  For more details, see the section on [built-in services](#built-in-services)
later in this document.

## Table of Contents

* [tl;dr: Quick Start](#tldr-quick-start)
 * [Lein Template](#lein-template)
 * [Hello World](#hello-world)
* [Credits and Origins](#credits-and-origins)
* [Bootstrapping](#bootstrapping)
* [Defining Services](#defining-services)
 * [Service Lifecycle](#service-lifecycle)
 * [Example Service](#example-service)
 * [Multi-arity Protocol Functions](#multi-arity-protocol-functions)
* [Referencing Services](#referencing-services)
 * [Individual Functions](#individual-functions)
 * [A Map of Functions](#a-map-of-functions)
 * [Prismatic Graph Binding Form](#prismatic-graph-binding-form)
 * [Via Service Protocol](#via-service-protocol)
* [Built-in Services](#built-in-services)
 * [Configuration Service](#configuration-service)
 * [Shutdown Service](#shutdown-service)
 * [nREPL Service](#nrepl-service)
* [Service Interfaces](#service-interfaces)
* [Command Line Arguments](#command-line-arguments)
* [Other Ways to Boot](#other-ways-to-boot)
* [Test Utils](#test-utils)
* [Trapperkeeper Best Practices](#trapperkeeper-best-practices)
 * [To Trapperkeeper Or Not To Trapperkeeper](#to-trapperkeeper-or-not-to-trapperkeeper)
 * [Separating Logic From Service Definitions](#separating-logic-from-service-definitions)
 * [On Lifecycles](#on-lifecycles)
 * [Testing Services](#testing-services)
* [Using the "Reloaded" Pattern](#using-the-reloaded-pattern)
* [Experimental Plugin System](#experimental-plugin-system)
* [Polyglot Support](#polyglot-support)
* [Dev Practices](#dev-practices)
* [Hopes and Dreams](#hopes-and-dreams)

## TL;DR: Quick Start

### Lein Template

A leinengen template is available that shows a suggested project structure:

    lein new trapperkeeper my.namespace/myproject

Note that the template is not intended to suggest a specific namespace organization;
it's just intended to show you how to write a service, a web service, and tests
for each.

### Hello World

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

Then, you need to define a Trapperkeeper bootstrap configuration file, which
simply lists the services that you want to load at startup.  This file should
be named `bootstrap.cfg` and should be located at the root of your classpath
(so, a good spot for it would be in your `resources` directory).

```clj
hello/hello-consumer-service
hello/hello-service
```

Lastly, set Trapperkeeper to be your `:main` in your leinengen project file:

```clj
:main puppetlabs.trapperkeeper.main
```

And now you should be able to run the app via `lein run`.  This example doesn't
do much; for a more interesting example that shows how you can use Trapperkeeper
to create a web application, check out the
[Example Web Service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/tree/master/examples/ring_app)
included in the Trapperkeeper webserver service project.

## Credits and Origins

Most of the heavy-lifting of the Trapperkeeper framework is handled by the
excellent [Prismatic Graph](https://github.com/Prismatic/plumbing) library.
To a large degree, Trapperkeeper just wraps some basic conventions and convenience
functions around that library, so many thanks go out to the fine folks at
Prismatic for sharing their code!

Trapperkeeper borrows some of the most basic concepts of the OSGi
"service registry" to allow users to create simple "services" and bind them together
in a single container, but it doesn't attempt to do any fancy classloading magic,
hot-swapping of code at runtime, or any of the other things that can make OSGi
and other similar application frameworks complex to work with.

## Bootstrapping

As mentioned briefly in the tl;dr section, Trapperkeeper relies on a `bootstrap.cfg`
file to determine the list of services that it should load at startup.  The other
piece of the bootstrapping equation is setting up a `main` that calls
Trapperkeeper's bootstrap code.  Here we'll go into a bit more detail about both
of these topics.

### `bootstrap.cfg`

The `bootstrap.cfg` file is a simple text file, in which each line contains the
fully qualified namespace and name of a service.  Here's an example `bootstrap.cfg`
that enables the nREPL service and a custom `foo-service`:

```
puppetlabs.trapperkeeper.services.nrepl.nrepl-service/nrepl-service
my.custom.namespace/foo-service
```

Note that it does not matter what order the services are specified in; trapperkeeper
will resolve the dependencies between them, and start and stop them in the
correct order based on their dependency relationships.

In normal use cases, you'll want to simply put `bootstrap.cfg` in your `resources`
directory and bundle it as part of your application (e.g. in an uberjar).  However,
there are cases where you may want to override the list of services (for development,
customizations, etc.).  To accommodate this, Trapperkeeper will actually search
in three different places for the `bootstrap.cfg` file; the first one it finds
will be used.  Here they are, listed in order of precedence:

* a location specified via the optional `--bootstrap-config` parameter on the
  command line when the application is launched
* in the current working directory
* on the classpath

## Defining Services

Trapperkeeper provides two constructs for defining services: `defservice` and
`service`.  As you might expect, `defservice` defines a service as a var in
your namespace, and `service` allows you to create one inline and assign it to
a variable in a let block or other location.  Here's how they work:

### `defservice`

`defservice` takes the following arguments:

* a service name
* an optional doc string
* an optional service protocol; only required if your service exports functions
  that can be used by other services
* a dependency list indicating other services/functions that this service requires
* a series of function implementations.  This must include all of the functions
  in the protocol if one is specified, and may also optionally provide override
  implementations for the built-in service `Lifecycle` functions.

#### Service Lifecycle

The service `Lifecycle` protocol looks like this:

```clj
(defprotocol Lifecycle
  (init [this context])
  (start [this context])
  (stop [this context]))
```

(This may look familiar; we chose to use the same function names as some of the
existing lifecycle protocols.  Ultimately we'd like to just use one of those
protocols directly, but for now our needs are different enough to warrant avoiding
the introduction of a dependency on an existing project.)

All service lifecycle functions are passed a service `context` map, which may
be used to store any service-specific state (e.g., a database connection pool or
some other object that you need to reference in subsequent functions.)  Services
may define these functions, `assoc` data into the map as needed, and then return
the updated context map.  The updated context map will be maintained by the
framework and passed to subsequent lifecycle functions for the service.

The default implementation of the lifecycle functions is to simply return
the service context map unmodified; if you don't need to implement a particular
lifecycle function for your service, you can simply omit it and the default
will be used.

Trapperkeeper will call the lifecycle functions in order based on the dependency
list of the services; in other words, if your service has a dependency on service
`Foo`, you are guaranteed that `Foo`'s `init` function will be called prior to
yours, and that your `stop` function will be called prior to `Foo`'s.

#### Example Service

Let's look at a concrete example:

```clj
;; This is the list of functions that the `FooService` must implement, and which
;; are available to other services who have a dependency on `FooService`.
(defprotocol FooService
  (foo1 [this x])
  (foo2 [this])
  (foo3 [this x]))

(defservice foo-service
   ;; docstring (optional)
   "A service that foos."

   ;; now we specify the (optional) protocol that this service satisfies:
   FooService

   ;; the :depends value should be a vector of vectors.  Each of the inner vectors
   ;; should begin with a keyword that matches the protocol name of another service,
   ;; which may be followed by any number of symbols.  Each symbol is the name of a
   ;; function that is provided by that service.  Trapperkeeper will fail fast at
   ;; startup if any of the specified dependency services do not exist, *or* if they
   ;; do not provide all of the functions specified in your vector.  (Note that
   ;; the syntax used here is actually just the
   ;; [fnk binding syntax from the Prismatic plumbing library](https://github.com/Prismatic/plumbing/tree/master/src/plumbing/fnk#fnk-syntax),
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
      (assoc context :foo (str "Some interesting state:" (function1)))

   ;; We could optionally also override the `start` and `stop` lifecycle
   ;; functions, but we won't for this example.

   ;; Now we'll define our service function implementations.  Again, we are
   ;; free to use the imported functions from the other services here:
   (foo1 [this x] ((comp function2 function3) x))
   (foo2 [this] (println "Function4 returns" (function4)))

   ;; We can also access the service context that we updated during the
   ;; lifecycle functions, by using the `service-context` function from
   ;; the `Service` protocol:
   (foo3 [this x]
     (let [context (service-context this)]
       (format "x + :foo is: '%s'" (str x (:foo context))))))
```

After this `defservice` statement, you will have a var named `foo-service` in
your namespace that contains the service.  You can reference this from a
Trapperkeeper bootstrap configuration file to include that service in your
app, and once you've done that your new service can be referenced as a dependency
(`{:depends [[:FooService ...`) by other services.

#### Multi-arity Protocol Functions

Clojure's protocols allow you to define multi-arity functions:

```clj
(defprotocol MultiArityService
   (foo [this x] [this x y]))
```

Trapperkeeper services can use the syntax from clojure's `reify` to implement
these multi-arity functions:

```clj
(defservice my-service
   MultiArityService
   []
   (foo [this x] x)
   (foo [this x y] (+ x y)))
```

### `service`

`service` works very similarly to `defservice`, but it doesn't define a var
in your namespace; it simply returns the service instance.  Here are some
examples (with and without protocols):

```clj
(service
   []
   (init [this context]
     (println "Starting anonymous service!")
     context))

(defprotocol AnotherService
   (foo [this]))
```

## Referencing Services

One of the most important features of Trapperkeeper is the ability to specify dependencies between services, and, thus, to reference functions provided by one service from functions in another service.  Trapperkeeper actually exposes several different ways to reference such functions, since the use cases may vary a great deal depending on the particular services involved.

### Individual Functions

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

This form expresses a dependency on two other services; one implementing the `BarService` protocol, and one implementing the `BazService` protocol.  It gives us a direct reference to the functions `bar-fn` and `baz-fn`.  We can call them as normal functions, without worrying about protocols any further.

### A Map of Functions

If we want to get simple references to plain-old functions from a service (again, without worrying about the protocols), but we don't want to have to list them all out explicitly in the binding form, we can do this:

```clj
(defservice foo-service
   [BarService BazService]
   (init [this context]
      ((:bar-fn BarService))
      ((:baz-fn BazService))
      context))
```

With this syntax, what we get access to are two local vars `BarService` and `BazService`, the value of each of which is a map.  The map keys are all keyword versions of the function names for all of the functions provided by the service protocol, and the values are the plain-old functions that you can just call directly.

### Prismatic Graph Binding Form

Both of the cases above are actually just specific examples of forms supported by the underlying Prismatic Graph library that we are using to manage dependencies.  If you're interested, the prismatic library offers some other ways to specify the binding forms and access your dependencies.  For more info, see the  [fnk binding syntax from the Prismatic plumbing library](https://github.com/Prismatic/plumbing/tree/master/src/plumbing/fnk#fnk-syntax).

### Via Service Protocol

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

## Built-in Services

Trapperkeeper includes a handful of built-in services that are intended to
remove some of the tedium of tasks that are common to most applications.  There
is a configuration service (which is responsible for loading the application
configuration and exposing it as data to other services), a shutdown service
(which provides some means for shutting down the container and allows other
services to register shutdown hooks), and an optional nREPL service (which
can be used to run an embedded REPL in your application, so that you can
connect to it from a remote process while it is running).

There are some other basic services available that don't ship with the
Trapperkeeper core, in order to keep the dependency tree to a minimum.  Of
particular interest is the [webserver service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9),
which you can use to run clojure Ring applications or java servlets.

Read on for more details about the built-in services.

### Configuration Service

The configuration service is built-in to Trapperkeeper and is always loaded.  It
performs the following tasks at application startup:

* Reads all application configuration into memory
* Initializes logging
* Provides functions that can be injected into other services to give them
  access to the configuration data

In its current form, the configuration service has some fairly rigid behavior.
(We hope to make it more dynamic in the future; for more info, see the
[Hopes and Dreams](#hopes-and-dreams) section below.)  Here's how it works:

#### Loading configuration data

All configuration data is read from config files on disk.  When launching a Trapperkeeper
application, you specify a ```--config``` command-line argument, whose value is
a file path.  You may specify the path to a single config file, or you may specify a
directory of config files.

We support several types of files for expressing the configuration data:

   * `.ini` files
   * `.edn` files (Clojure's [Extensible Data Notation](https://github.com/edn-format/edn) format)
   * `.conf` files (this is the [Human-Optimized Config Object Notation](https://github.com/typesafehub/config/blob/master/HOCON.md) format; a flexible superset of JSON defined by the [typesafe config library](https://github.com/typesafehub/config))
   * `.json` files
   * `.properties` files

The configuration service will then parse the config file(s) into memory as a
nested map; e.g., the section headers from an `.ini` file would become the top-level
keys of the map, and the values will be maps containing the individual setting names
and values from that section of the ini file.  (If using `.edn`, `.conf`, or
`.json`, you can control the nesting of the map more explicitly.)

Here's the protocol for the configuration service:

```clj
(defprotocol ConfigService
  (get-config [this] "Returns a map containing all of the configuration values")
  (get-in-config [this ks] [this ks default]
                 "Returns the individual configuration value from the nested
                 configuration structure, where ks is a sequence of keys.
                 Returns nil if the key is not present, or the default value if
                 supplied."))
```

Your service may then specify a dependency on the configuration service in order
to access service configuration data.

Here's an example.  Assume you have a directory called `conf.d`, and in it, you
have a single config file called `foo.ini` with the following contents

```ini
[foosection1]
foosetting1 = foo
foosetting2 = bar
```

Then, you can define a service like this:

```clj
(defservice foo-service
   [[:ConfigService get-in-config]]
   ;; service initialization code
   (init [this context]
     (println
      (format "foosetting2 has a value of '%s'"
         (get-in-config [:foosection1 :foosetting2])))
     context))
```

Then, if you add `foo-service` to your `bootstrap.cfg` file and launch your app
with `--config ./conf.d`, during initialization of the `foo-service` you should
see:

    foosetting2 has a value of 'bar'

#### Logging configuration

Trapperkeeper provides some automatic configuration for logging during application
startup.  This way, services don't have to deal with that independently, and all
services running in the same Trapperkeeper container will be able to share a
common logging configuration.  The built-in logging configuration
is compatible with `clojure.tools/logging`, so services can just call the
`clojure.tools/logging` functions and logging will work out of the box.

The logging implementation is based on [`logback`](http://logback.qos.ch/).
This means that Trapperkeeper will look for a `logback.xml` file on the
classpath, but you can override the location of this file via configuration.
This is done using the configuration setting `logging-config` in a `global`
section of your ini files.

`logback` is based on [`slf4j`](http://www.slf4j.org/), so it should be compatible
with the built-in logging of just about any existing Java libraries that your project
may depend on.  For more information on configuring logback, have a look at
[their documentation](http://logback.qos.ch/manual/configuration.html).

For example:

```INI
[global]
logging-config = /path/to/logback.xml
```

### Shutdown Service

The shutdown service is built-in to Trapperkeeper and, like the configuration
service, is always loaded.  It has two main responsibilities:

* Listen for a shutdown signal to the process, and initiate shutdown of the
  application if one is received (via CTRL-C or TERM signal)
* Provide functions that can be used by other services to initiate a shutdown
  (either because of a normal application termination condition, or in the event
  of a fatal error)

#### Shutdown Hooks

A service may implement the `stop` function from the `Lifecycle` protocol.  If so,
this function will be called during application shutdown.  The shutdown hook for
any given service is guaranteed to be called *before* the shutdown hook for any
of the services that it depends on.

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
      (bar-shutdown)
      context))
```

Given this service definition, the `bar-shutdown` function would be called
during shutdown of the Trapperkeeper container (during both a normal shutdown
or an error shutdown).  Because `bar-service` has a dependency on `foo-service`,
Trapperkeeper would also guarantee that the `bar-shutdown` is called *prior to*
the `stop` function for the `foo-service` (assuming `foo-service` provides one).

#### Provided Shutdown Functions

The shutdown service provides two functions that can be injected into other
services: `request-shutdown` and `shutdown-on-error`.  Here's the protocol:

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

##### `request-shutdown`

`request-shutdown` is a no-arg function that will simply cause Trapperkeeper to
initiate a normal shutdown of the application container (which will, in turn,
cause all registered shutdown hooks to be called).  It is asynchronous.

##### `shutdown-on-error`

`shutdown-on-error` is a higher-order function that is intended to be used as
a wrapper around some logic in your services; it will basically wrap your application
logic in a `try/catch` block that will cause Trapperkeeper to initiate an error
shutdown if an unhandled exception occurs in your block.  (This is generally
intended to be used on worker threads that your service may launch.)

`shutdown-on-error` accepts either two or three arguments: `[service-id f]` or
`[service-id f on-error-fn]`.

`service-id` is the id of your service; you can retrieve this via `(service-id this)`
inside of any of your service function definitions.

`f` is a function containing whatever application logic you desire; this is the
function that will be wrapped in `try/catch`.  `on-error-fn` is an optional callback
function that you can provide, which will be executed during error shutdown *if*
an unhandled exception occurs during the execution of `f`.  `on-error-fn` should
take a single argument: `context`, which is the service context map (the same
map that is used in the lifecycle functions).

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
      (my-normal-shutdown-fn)
      context))
```

In this scenario, the application would run for 10 seconds, and then the fatal
exception would be thrown.  Trapperkeeper would then call `my-error-cleanup-fn`,
and then attempt to call all of the normal shutdown hooks in the correct order
(including `my-normal-shutdown-fn`).

### nREPL Service

To assist in debugging applications, _Trapperkeeper_ comes with a service that
allows starting an embedded network REPL (`nREPL`) inside of the running
_Trapperkeeper_ process. See [Configuring the nREPL service](doc/nrepl-config.md)
for more information.

## Service Interfaces

One of the goals of Trapperkeeper's "service" model is that a service should
be thought of as simply an interface; any given service provides a
protocol as its "contract", and the implementation details
of these functions are not important to consumers.  (This borrows heavily
from OSGi's concept of a "service".)  This means that you can
write multiple implementations of a given service and swap them in and out of
your application by simply modifying your configuration, without having to change
any of the consuming code.  The Trapperkeeper
`webserver` service is an example of this pattern; we provide both a
[Jetty 7 webserver service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty7)
and a [Jetty 9 webserver service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9)
that can be used interchangeably.

One of the motivations behind this approach is to make it easier to ship
"on-premise" or "shrink-wrapped" software written in Clojure.  In SaaS
environments, the developers and administrators have tight control over what
components are used in an application, and can afford to be fairly rigid about
how things are deployed.  For on-premise software, the end user may need to
have a great deal more control over how components are mixed and matched to
provide a solution that scales to meet their needs; for example, a small shop
may be able to run 10 services on a single machine without approaching the
load capacity of the hardware, but a slightly larger shop might need to separate
those services out onto multiple machines.  Trapperkeeper provides an easy way
to do this at packaging time or configuration time, and the administrator does not
necessarily have to be familiar with clojure or EDN in order to effectively
configure their system.

Here's a concrete example of how this might work:

```clj
(ns services.foo)

(defprotocol FooService
  (foo [this]))

(ns services.foo.lowercase-foo
  (:require [services.foo :refer [FooService])

(defservice foo-service
   "A lower-case implementation of the `foo-service`"
   FooService
   []
   (foo [this] "foo"))

(ns services.foo.uppercase-foo
  (:require [services.foo :refer [FooService]))

(defservice foo-service
   "An upper-case implementation of the `foo-service`"
   FooService
   []
   (foo [this] "FOO"))

(ns services.foo-consumer)

(defprotocol FooConsumer
  (bar [this]))

(defservice foo-consumer
  "A service that consumes the `foo-service`"
  FooConsumer
  [[:FooService foo]]
  (bar [this]
    (format "Foo service returned: '%s'" (foo))))
```

Given this combination of services, you might have a `bootstrap.cfg` file
that looks like:

<pre>
services.foo-consumer/foo-consumer
services.foo.<strong>lowercase-foo</strong>/foo-service
</pre>

If you then ran your app, calling the function `bar` provided by the `foo-consumer`
service would yield: `"Foo service returned 'foo'"`.  If you then modified your
`bootstrap.cfg` file to look like:

<pre>
services.foo-consumer/foo-consumer
services.foo.<strong>uppercase-foo</strong>/foo-service
</pre>

Then the `bar` function would return `"Foo service returned 'bar'"`.  This allows
you to swap out a service implementation without making any code changes; you
need only modify your `bootstrap.cfg` file.

This is obviously a trivial example, but the same approach could be used to
swap out the implementation of something more interesting; a webserver, a message
queue, a persistence layer, etc.  This also has the added benefit of helping to
keep code more modular; a downstream service should only interact with a service
that it depends on through a well-known interface.

## Command Line Arguments

Trapperkeeper's default mode of operation is to handle the processing of application
command-line arguments for you.  This is done for a few reasons:

* It needs some data for bootstrapping
* Since the idea is that you will be composing multiple services together in a
  Trapperkeeper instance, managing command line options across multiple services
  can be tricky; using the configuration service is easier
* Who wants to process command-line arguments, anyway?

Note that if you absolutely need control over the command line argument processing,
it is possible to circumvent the built-in handling by calling Trapperkeeper's
`bootstrap` function directly; see additional details in the [Bootstrapping](#bootstrapping)
section below.

Trapperkeeper supports three command-line arguments:

* `--config/-c`: The path to the configuration file or directory.  This option
  is required, and is used to initialize the configuration service.
* `--bootstrap-config/-b`: This argument is optional; if specified, the value
  should be a path to a bootstrap configuration file that Trapperkeeper will use
  (instead of looking for `bootstrap.cfg` in the current working directory or
  on the classpath)
* `--debug/-d`: This option is not required; it's a flag, so it will evaluate
  to a boolean.  If `true`, sets the logging level to DEBUG, and also sets the
  `:debug` key in the configuration map provided by the configuration-service.

### `main` and Trapperkeeper

There are three different ways that you can initiate Trapperkeeper's bootstrapping
process:

#### Defer to Trapperkeeper's `main` function

In your leinengen project file, you can simply specify Trapperkeeper's `main` as
your `:main`:

    :main puppetlabs.trapperkeeper.main

Then you can simply use `lein run --config ...` to launch your app, or
`lein uberjar` to build an executable jar file that calls Trapperkeeper's `main`.

#### Call Trapperkeeper's `main` function from your code

If you don't want to defer to Trapperkeeper as your `:main` namespace, you can
simply call Trapperkeeper's `main` from your own code.  All that you need to do
is to pass along the command line arguments, which Trapperkeeper needs for
initializing bootstrapping, configuration, etc.  Here's what that might look
like:

```clj
(ns foo
   (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]))

(defn -main
  [& args]
  ;; ... any code you like goes here
  (apply trapperkeeper/main args))
```

#### Call Trapperkeeper's `run` function directly

If your application needs to handle command line arguments directly, rather than
allowing Trapperkeeper to handle them, you can circumvent Trapperkeeper's `main`
function and call `run` directly.

*NOTE* that if you intend to write multiple services and load them into the
same Trapperkeeper instance, it can end up being tricky to deal with varying
sets of command line options that are supported by the different services.  For
this reason, it is generably preferable to configure the services via the
configuration files and not rely on command-line arguments.

But, if you absolutely must... :)

Here's how it can be done:

```clj
(ns foo
   (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]))

(defn -main
   [& args]
   (let [my-processed-cli-args (process-cli-args args)
         trapperkeeper-options {:config           (my-processed-cli-args :config-file-path)
                                :bootstrap-config nil
                                :debug            false}]
      ;; ... other app initialization code
      (trapperkeeper/run trapperkeeper-options)))
```

Note that Trapperkeeper's `run` function requires a map as an argument,
and this map must contain the `:config` key which Trapperkeeper will use just
as it would have used the `--config` value from the command line.  You may also
(optionally) provide `:bootstrap-config` and `:debug` keys, to override the
path to the bootstrap configuration file and/or enable debugging on the application.

#### Other Ways to Boot

We use the term `boot` to describe the process of building up an instance of
a `TrapperkeeperApp`, and then calling `init` and `start` on all of its services
in the correct order.

It is possible to use the Trapperkeeper framework at a slightly lower level.  Using
`run` or `main` will boot all of the services and then block the main thread until a
shutdown is triggered; if you need more control, you'll be getting a reference
to a `TrapperkeeperApp` directly.

##### `TrapperkeeperApp` protocol

There is a protocol that represents a Trapperkeeper application:

```clj
(defprotocol TrapperkeeperApp
  "Functions available on a Trapperkeeper application instance"
  (app-context [this] "Returns the application context for this app (an atom containing a map)")
  (init [this] "Initialize the services")
  (start [this] "Start the services")
  (stop [this] "Stop the services"))
```

With a reference to a `TrapperkeeperApp`, you can gain more control over when
the lifecycle functions are called.  To get an instance, you can call any of
these functions:

* `(boot-with-cli-data [cli-data])`: this function expects you to process your
  own cli args into a map (as with `run`).  It then creates a TrapperkeeperApp,
  boots all of the services, and returns the app.
* `(boot-services-with-cli-data [services cli-data])`: this function expects you
  to process your own cli args into a map, and also to build up your own list
  of services to pass in as the first arg.  It circumvents the normal
  Trapperkeeper `bootstrap.cfg` process, creates a `TrapperkeeperApp` with all
  of your services, boots them, and returns the app.
* `(boot-services-with-config [services config])`: this function expects you
  to process your own cli args, configuration data, and build up your own list
  of services.  You pass it the list of services and the map of all service
  configuration data, and it circumvents the normal `bootstrap.cfg` process,
  creates a `TrapperkeeperApp` with all of your services, boots them, and
  returns the app.

Each of the above gives you a way to get a reference to a `TrapperkeeperApp`
without blocking the main thread to wait for shutdown.  If, later, you do wish
to wait for the shutdown, you can simply call `run-app` and pass it your
`TrapperkeeperApp`.  Alternately, you can call `stop` on the `TrapperkeeperApp`
to initiate shutdown on your own terms.

Note that all of these functions *do* boot your services.  If you wish to have
more control over the booting of the services, you can use this function:

* `(build-app [services config-data])`: this function creates a `TrapperkeeperApp`
  *without* booting the services.  You can then boot them yourself by calling
  `init` and `start` on the `TrapperkeeperApp`.

## Test Utils

Trapperkeeper provides some [utility code](./test/puppetlabs/trapperkeeper/testutils)
for use in tests.  The code is available in a separate "test" jar that you may depend
on by using a classifier in your project dependencies.

```clojure
  (defproject yourproject "1.0.0"
    ...
    :profiles {:dev {:dependencies [[puppetlabs/trapperkeeper "x.y.z" :classifier "test"]]}})
```

This library includes some utilities to help test logging functionality, as well
as to test your services by bootstrapping a _Trapperkeeper_ application instance
in your test.  See the [Trapperkeeper Test Utils](doc/test-utils.md) for more
information.

## Trapperkeeper Best Practices

Here are some general guidelines for writing Trapperkeeper services.

### To Trapperkeeper Or Not To Trapperkeeper

Trapperkeeper gives us a lot of flexibility on how we decide to package and
deploy applications and services.  When should you use it?  The easiest rule of
thumb is: if it's possible to expose your code as a simple library with no
dependencies on Trapperkeeper, it's highly preferable to go that route.  Here are
some things that might be reasonable indicators that you should consider exposing
your code via a Trapperkeeper service:

* You're writing a clojure web service and there is a greater-than-zero percent
  chance that you will eventually want to be able to run it inside of the same
  embedded web server instance as another web service.
* Your code initializes some long-lived, stateful resource that needs to be used
  by other code, and that other code might not want/need to be responsible for
  explicitly managing the lifecycle of your resource
* Your code has a need for a managed lifecycle; initialization / startup,
  shutdown / cleanup
* Your code has a dependency on some other code that has a managed lifecycle
* Your code requires external configuration that you would like to make consistent
  with other puppetlabs / Trapperkeeper applications

### Separating Logic From Service Definitions

In general, it's a good idea to keep the code that implements your business logic
completely separated from the Trapperkeeper service binding.  This makes it much
easier to test your functions as functions, without the need to boot up the whole
framework.  It also makes your code more re-usable and portable.  Here's a more
concrete example.

DON'T DO THIS:

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

This way, you can test `calculator.core` directly, and re-use the functions it
provides in other places without having to worry about Trapperkeeper.

### On Lifecycles

Trapperkeeper provides three lifecycle functions: init, start, and stop.  Hopefully
"stop" is pretty obvious.  We've had some questions, though, about what the difference
is between "init" and "start".  Trapperkeeper doesn't impose a hard-and-fast rule
that you must follow for how you use these, but here are some data points:

* The 'init' function of any service that you depend on will always be called
  before your 'init', and before any 'start'.  The 'start' function of any service
  that you depend on will always be called before your 'start'.
* Trapperkeeper itself doesn't impose any semantics about what kinds of things you
  should do in each of those lifecycle phases.  It's more about giving services
  the flexibility to establish a contract with other services.  e.g., a webserver
  service may specify that it only accepts the registration of web handlers during
  the 'init' phase, and that no new handlers can be added after it has completed
  its 'start' phase.  (This is just a theoretical example, this restriction isn't
  actually true for our current jetty implementations.)
* The lifecycles are relatively new; as people start to use these lifecycles a
  bit more, we may end up shaking out a more concrete best-practice pattern.
  It's also possible we might end up introducing another phase or two to give more
  granularity... for now, we wanted to try to keep it fairly simple and flexible,
  and get a handle on what kinds of use cases people end up having for it.

### Testing Services

As we mentioned before, it's better to separate your business logic from your
service definitions as much as possible, so that you can test your business
logic functions directly.  Thus, the vast majority of your tests should not need
to involve Trapperkeeper at all.  However, you probably will want to have a small
handful of tests that do boot up a full Trapperkeeper app, so that you can verify
that your dependencies work as expected, etc.

When writing tests that boot a Trapperkeeper app, the best way to do it is to
use the helper testutils macros that we describe in the
[testutils documentation](./doc/test-utils.md).  They will
take care of things like making sure the application is shut down cleanly after
the test, and will generally just make your life easier :)

## Using the "Reloaded" Pattern

[Stuart Sierra's "reloaded" workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded)
has become very popular in the clojure world of late; and for good reason, it's
an awesome and super productive way to do interactive development in the REPL,
and also helps encourage code modularity and minimizing mutable state.  He
has some [example code](https://github.com/stuartsierra/component#reloading)
that shows some utility functions to use in the REPL to interact with your application.

Trapperkeeper was designed with this pattern in mind as a goal.  Thus, it's
entirely possible to write some very similar code that allows you to start/stop/reload
your app in a REPL:
```clj
(ns examples.my-app.repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [examples.my-app.services :refer [count-service foo-service baz-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh)]))

;; a var to hold the main `TrapperkeeperApp` instance.
(def system nil)

(defn init []
  (alter-var-root #'system
    (fn [_] (let [app (tk/build-app
                        [jetty9-service count-service foo-service baz-service]
                        {:global    {:logging-config "examples/my_app/logback.xml"}
                         :webserver {:port 8080}
                         :example   {:my-app-config-value "FOO"}})]
              (tka/init app)))))

(defn start []
  (alter-var-root #'system tka/start))

(defn stop []
  (alter-var-root #'system
    (fn [s] (when s (tka/stop s)))))

(defn go []
  (init)
  (start))

(defn context []
  @(tka/app-context system))

;; pretty print the entire application context
(defn print-context []
  (clojure.pprint/pprint (context)))

(defn reset []
  (stop)
  (refresh :after 'examples.ring-app.repl/go))
```

For a working example, see the `repl` namespace in the
[jetty9 example app](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/tree/master/examples/ring_app)

## Experimental Plugin System

Trapperkeeper has an **extremely** simple, experimental plugin mechanism.  It allows you to
specify (as a command-line argument) a directory of "plugin" .jars that will be
dynamically added to the classpath at runtime.  Each .jar will also be checked
for duplicate classes or namespaces before it is added, so as to prevent any
unexpected behavior.

This provides the ability to extend the functionality of a deployed,
Trapperkeeper-based application by simply including one or more services
packaged into standalone "plugin" .jars, and adding the additional service(s)
to the bootstrap configuration.

Projects that wish to package themselves as "plugin" .jars should build an
uberjar containing all of their dependencies.  However, there is one caveat
here - Trapperkeeper *and all of its depenedencies* should be excluded from the
uberjar.  If the exclusions are not defined correctly, Trapperkeeper will fail
to start because there will be duplicate versions of classes/namespaces on the
classpath.

Plugins are specified via a command-line argument:
`--plugins /path/to/plugins/directory`; every .jar file in that directory will
be added to the classpath by Trapperkeeper.

## Polyglot Support

It should be possible (when extenuating circumstances necessitate it) to integrate
code from just about any JVM language into a Trapperkeeper application.  At the
time of this writing, the only languages we've really experimented with are Java
and Ruby (via JRuby).

For Java, the Trapperkeeper webserver service contains an
[example servlet app](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/tree/master/examples/servlet_app),
which illustrates how you can run a Java servlet in trapperkeeper's webserver.

We have also included a simple example of wrapping a Java library in a Trapperkeeper
service, so that it can provide functions to other services.  Have a look at
the code for the [example java service provider app](examples/java_service) for
more info.

For Ruby, we've been able to write an alternate implementation of a `webserver-service`
which provides an `add-rack-handler` function for running Rack applications inside
of Trapperkeeper.  We've also been able to illustrate the ability to call clojure
functions provided by existing clojure Trapperkeeper services from the Ruby code
in such a Rack application.  This code isn't necessarily production quality yet,
but if you're interested, have a look at the
[trapperkeeper-ruby project on github](https://github.com/puppetlabs/trapperkeeper-ruby).

## Dev Practices

There's nothing really special about developing a Trapperkeeper application as
compared to any other clojure application, but there are a couple of things we've
found useful:

### Leinengen's `checkouts` feature

Since Trapperkeeper is intended to help modularize applications, it also increases
the likelihood that you'll end up working with more than one code base / git repo
at the same time.  When you find yourself in this situation, leinengen's
[checkouts](http://jakemccrary.com/blog/2012/03/28/working-on-multiple-clojure-projects-at-once/)
feature is very useful.

### Leinengen's `trampoline` feature

If you need to test the shutdown behavior of your application, you may find yourself
trying to do `lein run` and then sending a CTRL-C or `kill`.  However, due to the
way leinengen manages JVM processes, this CTRL-C will be handled by the lein process
and won't actually make it to Trapperkeeper.  If you need to test shutdown
functionality, you'll want to use `lein trampoline run`.

However, one quirk that we've discovered is that it does not appear that lein's
`checkouts` and `trampoline` features work together; thus, when you run the app
via `lein trampoline`, the classpath will not include the projects in the
`checkouts` directory.  Thus, you'll need to do `lein install` on the `checkouts`
projects to copy their jars into your `.m2` directory before running `lein trampoline run`.

## Hopes and Dreams

Here are some ideas that we've had and things we've played around with a bit for
improving Trapperkeeper in the future.

### More flexible configuration service

The current configuration service is hard-coded to use files (`.ini`, `.edn`,
`.conf`, `.json`, or `.properties`) as its back
end, requires a `--config` argument on the CLI, and is hard-coded to use
`logback` to initialize logging.  We'd like to make all of those more flexible;
e.g., to support other persistence mechanisms,
perhaps allow dynamic modifications to configuration values, support other
logging frameworks, etc.  These changes will probably require us to make the
service life cycle just a bit more complex, though, so we didn't tackle them
for the initial releases.

### Alternate implementations of the webserver service

We currently provide both a
[Jetty 7](https://github.com/puppetlabs/trapperkeeper-webserver-jetty7) and a
[Jetty 9](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9)
implementation of the web server service.  We may also experiment with some other
options such as Netty.

### Add support for other types of web applications

The current `:webserver-service` interface provides functions for registering
a [Ring](https://github.com/ring-clojure/ring) or
[Servlet](http://docs.oracle.com/javaee/7/api/javax/servlet/Servlet.html) application.
We'd like to add a few more similar functions that would allow you to register other
types of web applications, specifically an `add-rack-handler` function that would allow
you to register a Rack application (to be run via JRuby).

## License

Copyright © 2013 Puppet Labs

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
