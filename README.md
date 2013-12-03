# trapperkeeper

Trapperkeeper is a lightweight clojure application / service framework.  It
borrows some of the most basic concepts of the OSGi "service registry" to allow
users to create simple "services" and bind them together in a single
container, but it doesn't attempt to do any fancy classloading magic, hot-swapping
of code at runtime, or any of the other things that can make OSGi and other similar
application frameworks complex to work with.

A "service" in trapperkeeper is represented as simply a map of clojure functions.
Each service can advertise the functions that it provides, as well as a list of
other services that it has a dependency on.  You then configure trapperkeeper with
a list of services to run and launch it.  At startup, it validates that all of the
dependencies are met and fails fast if they are not.  If they are, then it injects
the dependency functions into each service and starts them all up in the correct
order.

Trapperkeeper provides a few built-in services such as a configuration service,
a shutdown service, and a webserver service.  Your custom services can specify
dependencies on these and leverage the functions that they provide.  For more
details, see the section on [built-in services](#built-in-services) later in this document.

## Credits

Most of the heavy-lifting of the trapperkeeper framework is handled by the
excellent [Prismatic Graph](https://github.com/Prismatic/plumbing) library.
To a large degree, trapperkeeper just wraps some basic conventions and convenience
functions around that library, so many thanks go out to the fine folks at
Prismatic for sharing their code!

## TOC

* [tl;dr: Quick Start](#tldr-quick-start)
* [Defining Services](#defining-services)
* [Built-in Services](#built-in-services)
 * [Configuration Service](#configuration-service)
 * [Shutdown Service](#shutdown-service)
 * [Webserver Service](#webserver-service)
* [Command Line Arguments](#command-line-arguments)
* [Bootstrapping](#bootstrapping)
* [Dev Practices](#dev-practices)
* [Test Utils](#test-utils)
* [Hopes and Dreams](#hopes-and-dreams)

## TL;DR: Quick Start

Here's a "hello world" example for getting started with trapperkeeper.

First, you need to define one or more services:

```clj
(ns hello
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defservice hello-service
  ;; specify dependencies, and functions that our service provides
  {:depends []
   :provides [hello]}
  ;; execute any necessary initialization code
  (println "Hello service initializing!")
  ;; return a map containing the functions that we said we'd provide
  {:hello (fn [] (println "Hello there!"))})

(defservice hello-consumer-service
  ;; express a dependency on the `hello` function from the `hello-service`.
  {:depends [[:hello-service hello]]
   :provides []}
  (println "Hello consumer initializing; hello service says:")
  ;; call the function from the `hello-service`!
  (hello)
  ;; we don't provide any functions from this service, so return an empty map.
  {})
```

Then, you need to define a trapperkeeper bootstrap configuration file, which
simply lists the services that you want to load at startup.  This file should
be named `bootstrap.cfg` and should be located at the root of your classpath
(so, a good spot for it would be in your `resources` directory).

```clj
hello/hello-consumer-service
hello/hello-service
```

Lastly, set trapperkeeper to be your `:main` in your leinengen project file:

```clj
:main puppetlabs.trapperkeeper.main
```

And now you should be able to run the app via `lein run`.  This example doesn't
do much; for a more interesting example that shows how you can use trapperkeeper
to create a web application, see TODO JUSTIN LINK HERE.

## Defining Services

Trapperkeeper provides two constructs for defining services: `defservice` and
`service`.  As you might expect, `defservice` defines a service as a var in
your namespace, and `service` allows you to create one inline and assign it to
a variable in a let block or other location.  Here's how they work:

### `defservice`

`defservice` takes the following arguments:

* a service name
* an optional doc string
* a map specifying the dependencies and listing the functions that the service provides
* a body, which may be any number of forms but must return a map containing the
  functions that were indicated in the 'provides' value from the previous argument.

Let's look at a concrete example:

```clj
(defservice foo-service
   ;; docstring (optional)
   "A service that foos."
   ;; depends/provides metadata map
   {
    ;; the :depends value should be a vector of vectors.  Each of the inner vectors
    ;; should begin with a keyword that matches the name of another service, which
    ;; may be followed by any number of symbols.  Each symbol is the name of a function
    ;; that is provided by that service.  Trapperkeeper will fail fast at startup
    ;; if any of the specified dependency services do not exist, *or* if they
    ;; do not provide all of the functions specified in your vector.
    :depends [[:some-service function1 function2]
              [:another-service function3 function4]]
    ;; the :provides value is simply a vector of symbols; each symbol is the name
    ;; of a function that your service promises to provide in its output map.
    :provides [foo1 foo2 foo3]
   }

   ;; After your metadata map comes the body of the service; this is the code
   ;; that will be executed when the service is started by trapperkeeper.  You
   ;; may specify any number of forms here, but the final value returned by your
   ;; body must be a map whose keys are keywords that correspond with what you've
   ;; specified in your `:provides` metadata, and whose values are functions that
   ;; may be used by other services.
   ;;
   ;; inside your body, you may use the functions that were specified in your
   ;; `:depends` metadata just like you would use any other function:
   (let [someval (function1)]
      ;; do some other initialization
      ;; ...
      ;; now return our service function map.  we said we'd provide functions
      ;; `foo1`, `foo2`, and `foo3`, so we need to do that:
      {:foo1 (comp function2 function3)
       :foo2 #(println "Function4 returns" (function4))
       :foo3 (fn [x] (format "x + function1 is: '%s'" (str x someval)))}))
```

After this `defservice` statement, you will have a var named `foo-service` in
your namespace that contains the service.  You can reference this from a
trapperkeeper bootstrap configuration file to include that service in your
app, and once you've done that your new service can be referenced as a dependency
(`{:depends [[:foo-service ...`) by other services.

### `service`

`service` works very similarly to `defservice`, but it doesn't define a var
in your namespace; it simply returns the service instance.  It also expects
a keyword value to name the service instead of the var name that you use with
`defservice`.  Here's an example:

```clj
(service :bar-service
   "A service that bars."
   {:depends []
    :provides [bar]}
   ;; initialization code goes here, then we return our service function map
   {:bar (fn [] "bar")})
```

## Built-in Services

Trapperkeeper includes a handful of built-in services that are intended to
remove some of the tedium of tasks that are common to most applications.  There
is a configuration service (which is responsible for loading the application
configuration and exposing it as data to other services), a shutdown service
(which provides some means for shutting down the container and allows other
services to register shutdown hooks), and an optional web server service (which
can be used to register one or more web handlers).  Read on for more details.

### Configuration Service

The configuration service is built-in to trapperkeeper and is always loaded.  It
performs the following tasks at application startup:

* Reads all application configuration into memory
* Initializes logging
* Provides functions that can be injected into other services to give them
  access to the configuration data

In its current form, the configuration service has some fairly rigid behavior.
(We hope to make it more dynamic in the future; for more info, see the
[Hopes and Dreams](#hopes-and-dreams) section below.)  Here's how it works:

#### Loading configuration data

All configuration data is read from `ini` files.  When launching a trapperkeeper
application, you specify a ```--config``` command-line argument, whose value is
a file path.  You may specify the path to a single file, or you may specify a directory
of .ini files.

The configuration service will then parse the ini file(s) into memory as a map;
the keys of the map will be keywords representing the section headers from the
ini file(s), and the values will be maps containing the individual setting names
and values from that section of the ini file.

The configuration service then provides two functions that you can specify as
dependencies for other services: `get-config []` and `get-in-config [ks]`.  The
first returns the full configuration map; the second is like clojure's `get-in`
function, and allows you to retrieve data from an arbitrary path in the
configuration map.

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
   {:depends [[:config-service get-in-config]]
    :provides []}
   ;; service initialization code
   (println
      (format "foosetting2 has a value of '%s'"
         (get-in-config :foosection1 :foosetting2)))
   ;; return empty service function map
   {})
```

Then, if you add `foo-service` to your `bootstrap.cfg` file and launch your app
with `--config ./conf.d`, during initialization of the `foo-service` you should
see:

    foosetting2 has a value of 'bar'

#### Logging configuration

Trapperkeeper provides some automatic configuration for logging during application
startup.  This way, services don't have to deal with that independently, and all
services running in the same trapperkeeper container will be able to share a
common logging configuration.  The built-in logging configuration
is compatible with `clojure.tools/logging`, so services can just call the
`clojure.tools/logging` functions and logging will work out of the box.

The current implementation of the logging initialization is based on `log4j`
(though we plan to make this more flexible in the future; see the
[Hopes and Dreams](#hopes-and-dreams) section below).  This means that
trapperkeeper will look for a `log4j.properties` file on the classpath.  You can
override the location of this file via configuration, though; this is done
using the configuration setting `logging-config` in a `global` section of your
ini files.

For example:

```INI
[global]
logging-config = /path/to/log4j.properties
```

### Shutdown Service

The shutdown service is built-in to trapperkeeper and, like the configuration
service, is always loaded.  It has three main responsibilities:

* Listen for a shutdown signal to the process, and initiate shutdown of the
  application if one is received (via CTRL-C or TERM signal)
* Allow other services to register their own shutdown hooks, which will be
  called the correct order during normal shutdown
* Provide functions that can be used by other services to initiate a shutdown
  (either because of a normal application termination condition, or in the event
  of a fatal error)

#### Shutdown Hooks

To register a shutdown hook, a service need only provide a no-arg `:shutdown`
function in its service function map.  For example:

```clj
(defn bar-shutdown
   []
   (log/info "bar-service shutting down!"))

(defservice bar-service
   {:depends [[:foo-service foo]]
    :provides [:shutdown]}
   ;; service initialization code
   (log/info "bar-service initializing.")
   ;; return service function map
   {:shutdown bar-shutdown})
```

Given this service definition, the `bar-shutdown` function would be called
during shutdown of the trapperkeeper container (during both a normal shutdown
or an error shutdown).  Because `bar-service` has a dependency on `foo-service`,
trapperkeeper would also guarantee that the `bar-shutdown` is called *prior to*
the shutdown hook for `foo-service` (assuming `foo-service` provides one).

#### Provided Shutdown Functions

The shutdown service provides two functions that can be injected into other
services: `request-shutdown` and `shutdown-on-error`.  To use them, you may
simply specify a dependency on them:

```clj
(defservice baz-service
   {:depends [[:shutdown-service request-shutdown shutdown-on-error]]
    :provides []}
   ;; initialization
   ;; ...
   ;; return service function map
   {})
```

##### `request-shutdown`

`request-shutdown` is a no-arg function that will simply cause trapperkeeper to
initiate a normal shutdown of the application container (which will, in turn,
cause all registered shutdown hooks to be called).  It is asynchronous.

##### `shutdown-on-error`

`shutdown-on-error` is a higher-order function that is intended to be used as
a wrapper around some logic in your services; it will basically wrap your application
logic in a `try/catch` block that will cause trapperkeeper to initiate an error
shutdown if an unhandled exception occurs in your block.  (This is generally
intended to be used on worker threads that your service may launch.)

`shutdown-on-error` accepts either one or two arguments: `[f]` or `[f on-error-fn]`.
`f` is a function containing whatever application logic you desire; this is the
function that will be wrapped in `try/catch`.  `on-error-fn` is an optional callback
function that you can provide, which will be executed during error shutdown *if*
an unhandled exception occurs during the execution of `f`.

Here's an example:

```clj
(defn my-work-fn
   []
   ;; do some work
   (Thread/sleep 10000)
   ;; uh-oh!  An unhandled exception!
   (throw (IllegalStateException. "egads!")))

(defn my-error-cleanup-fn
   []
   (log/info "Something terrible happened!")
   (log/info "Performing shutdown logic that should only happen on a fatal error."))

(defn my-normal-shutdown-fn
   []
   (log/info "Performing normal shutdown logic."))

(defservice yet-another-service
   {:depends [[:shutdown-service shutdown-on-error]]
    :provides [:shutdown]}
   ;; initialization
   (let [worker-thread (future (shutdown-on-error my-work-fn my-error-cleanup-fn))]
      ;; return service function map
      {:shutdown my-normal-shutdown-fn}))
```

In this scenario, the application would run for 10 seconds, and then the fatal
exception would be thrown.  Trapperkeeper would then call `my-error-cleanup-fn`,
and then attempt to call all of the normal shutdown hooks in the correct order
(including `my-normal-shutdown-fn`).

### Webserver Service

The webserver service is built-in to trapperkeeper, but is optional.  It is not
loaded into your trapperkeeper application unless you specifically reference it
in your `bootstrap.cfg` file, via:

    puppetlabs.trapperkeeper.services.jetty.jetty-service/webserver-service

Note that trapperkeeper currently only provides one implementation of the
`webserver-service` interface, which is based on Jetty 7.  However, the interface
is intended to be agnostic to the underlying web server implementation, which
will allow us to provide alternate implementations in the future.  Trapperkeeper
applications will then be able to switch to a different web server implementation
by changing only their `bootstrap.cfg` file--no code changes.  (For more info,
see the [Hopes and Dreams](#hopes-and-dreams) section below).

The web server is configured via the configuration service; so, you can control
various properties of the server (ports, SSL, etc.) by adding a `[webserver]`
section to one of your configuration ini files, and setting various properties
therein.  For more info, see [TODO LINK: Configuring the Web Server](.).

The `webserver-service` is currently geared towards web applications built using
the clojure [Ring](https://github.com/ring-clojure/ring) library.  (We hope to
add support for a few more different types of web applications in the future;
see the [Hopes and Dreams](#hopes-and-dreams) section for more info.)

The current implementation of the `webserver-service` provides two functions:
`add-ring-handler` and `join`.

#### `add-ring-handler`

`add-ring-handler` takes two arguments: `[handler path]`.  The `handler` argument
is just a normal Ring application (the same as what you would pass to `run-jetty`
if you were using the `ring-jetty-adapter`).  The `path` is a URL prefix / context
string that will be prepended to all your handler's URLs; this is key to allowing
the registration of multiple handlers in the same web server without the possibility
of URL collisions.  So, for example, if your ring handler has routes `/foo` and
`/bar`, and you call:

```clj
(add-ring-handler my-app "/my-app")
```

Then your routes will be served at `/my-app/foo` and `my-app/bar`.

You may specify `""` as the value for `path` if you are only registering a single
handler and do not need to prefix the URL.

Here's an example of how to use the `:webserver-service`:

```clj
(defservice my-web-service
   {:depends [[:webserver-service add-ring-handler]]
    :provides []}
   ;; initialization
   (add-ring-handler my-app "/my-app")
   ;; return service function map
   {})
```

*NOTE FOR COMPOJURE APPS*: If you are using compojure, it's important to note
that compojure requires use of the [`context` macro](https://github.com/weavejester/compojure/wiki/Nesting-routes)
in order to support nested routes.  So, if you're not already using `context`,
you will need to do something like this:

```clj
(ns foo
   (:require [compojure.core :refer [context]]
   ;;...
   ))

(defservice my-web-service
   {:depends [[:webserver-service add-ring-handler]]
    :provides []}
   ;; initialization
   (let [context-path "/my-app"
         context-app  (context context-path [] my-compojure-app)]
     (add-ring-handler context-app context-path))
   ;; return service function map
   {})
```

#### `join`

This function is not recommended for normal use, but is provided for compatibility
with the `ring-jetty-adapter`.  `ring-jetty-adapter/run-jetty`, by default,
calls `join` on the underlying Jetty server instance.  This allows your thread
to block until Jetty shuts down.  This should not be necessary for normal
trapperkeeper usage, because trapperkeeper already blocks the main thread and
waits for a termination condition before allowing the process to exit.  However,
if you do need this functionality for some reason, you can simply call `(join)`
to cause your thread to wait for the Jetty server to shut down.

## Command Line Arguments

Trapperkeeper's default mode of operation is to handle the processing of application
command-line arguments for you.  This is done for a few reasons:

* It needs some data for bootstrapping
* Since the idea is that you will be composing multiple services together in a
  trapperkeeper instance, managing command line options across multiple services
  can be tricky; using the configuration service is easier
* Who wants to process command-line arguments, anyway?

Note that if you absolutely need control over the command line argument processing,
it is possible to circumvent the built-in handling by calling trapperkeeper's
`bootstrap` function directly; see additional details in the [Bootstrapping](#bootstrapping)
section below.

Trapperkeeper supports three command-line arguments:

* `--config/-c`: The path to the configuration file or directory.  This option
  is required, and is used to initialize the configuration service.
* `--bootstrap-config/-b`: This argument is optional; if specified, the value
  should be a path to a bootstrap configuration file that trapperkeeper will use
  (instead of looking for `bootstrap.cfg` in the current working directory or
  on the classpath)
* `--debug/-d`: This option is not required; it's a flag, so it will evaluate
  to a boolean.  If `true`, sets the logging level to DEBUG, and also sets the
  `:debug` key in the configuration map provided by the configuration-service.

## Bootstrapping

As mentioned briefly in the tl;dr section, trapperkeeper relies on a `bootstrap.cfg`
file to determine the list of services that it should load at startup.  The other
piece of the bootstrapping equation is setting up a `main` that calls
trapperkeeper's bootstrap code.  Here we'll go into a bit more detail about both
of these topics.

### `bootstrap.cfg`

The `bootstrap.cfg` file is a simple text file, in which each line contains the
fully qualified namespace and name of a service.  Here's an example `bootstrap.cfg`
that enables the jetty web server service and a custom `foo-service`:

```
puppetlabs.trapperkeeper.services.jetty.jetty-service/webserver-service
my.custom.namespace/foo-service
```

Note that it does not matter what order the services are specified in; trapperkeeper
will resolve the dependencies between them, and start and stop them in the
correct order based on their dependency relationships.

In normal use cases, you'll want to simply put `bootstrap.cfg` in your `resources`
directory and bundle it as part of your application (e.g. in an uberjar).  However,
there are cases where you may want to override the list of services (for development,
customizations, etc.).  To accommodate this, trapperkeeper will actually search
in three different places for the `bootstrap.cfg` file; the first one it finds
will be used.  Here they are, listed in order of precedence:

* a location specified via the optional `--bootstrap-config` parameter on the
  command line when the application is launched
* in the current working directory
* on the classpath

### `main` and trapperkeeper

There are three different ways that you can initiate trapperkeeper's bootstrapping
process:

#### Defer to trapperkeeper's `main` function

In your leinengen project file, you can simply specify trapperkeeper's `main` as
your `:main`:

    :main puppetlabs.trapperkeeper.main

Then you can simply use `lein run --config ...` to launch your app, or
`lein uberjar` to build an executable jar file that calls trapperkeeper's `main`.

#### Call trapperkeeper's `main` function from your code

If you don't want to defer to trapperkeeper as your `:main` namespace, you can
simply call trapperkeeper's `main` from your own code.  All that you need to do
is to pass along the command line arguments, which trapperkeeper needs for
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

#### Call trapperkeeper's bootstrap function directly

If your application needs to handle command line arguments directly, rather than
allowing trapperkeeper to handle them, you can circumvent trapperkeeper's `main`
functions and call into the bootstrapping code directly.

*NOTE* that if you intend to write multiple services and load them into the
same trapperkeeper instance, it can end up being tricky to deal with varying
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
      (trapperkeeper/bootstrap trapperkeeper-options)))
      ;; TODO: NATE, DO WE NEED TO CALL 'RUN' HERE?
```

Note that trapperkeeper's `bootstrap` function requires a map as an argument,
and this map must contain the `:config` key which trapperkeeper will use just
as it would have used the `--config` value from the command line.  You may also
(optionally) provide `:bootstrap-config` and `:debug` keys, to override the
path to the bootstrap configuration file and/or enable debugging on the application.

## Dev Practices

There's nothing really special about developing a trapperkeeper application as
compared to any other clojure application, but there are a couple of things we've
found useful:

### Leinengen's `checkouts` feature

Since trapperkeeper is intended to help modularize applications, it also increases
the likelihood that you'll end up working with more than one code base / git repo
at the same time.  When you find yourself in this situation, leinengen's
[checkouts](http://jakemccrary.com/blog/2012/03/28/working-on-multiple-clojure-projects-at-once/)
feature is very useful.

### Leinengen's `trampoline` feature

If you need to test the shutdown behavior of your application, you may find yourself
trying to do `lein run` and then sending a CTRL-C or `kill`.  However, due to the
way leinengen manages JVM processes, this CTRL-C will be handled by the lein process
and won't actually make it to trapperkeeper.  If you need to test shutdown
functionality, you'll want to use `lein trampoline run`.

However, one quirk that we've discovered is that it does not appear that lein's
`checkouts` and `trampoline` features work together; thus, when you run the app
via `lein trampoline`, the classpath will not include the projects in the
`checkouts` directory.  Thus, you'll need to do `lein install` on the `checkouts`
projects to copy their jars into your `.m2` directory before running `lein trampoline run`.

## Test Utils

Trapperkeeper provides some [utility code](./test/puppetlabs/trapperkeeper/testutils)
for use in tests.  The code is available in a separate "test" jar that you may depend
on by using a classifier in your project dependencies.

```clojure
  (defproject yourproject "1.0.0"
    ...
    :profiles {:test {:dependencies [[puppetlabs/trapperkeeper "x.y.z" :classifier "test"]]}})
```

For more details, see the [TODO LINK: Test Utils README](.)

## Hopes and Dreams

Here are some ideas that we've had and things we've played around with a bit for
improving trapperkeeper in the future.

### More flexible configuration service

The current configuration service is hard-coded to use `ini` files as its back
end, requires a `--config` argument on the CLI, and is hard-coded to use
`log4j` to initialize logging.  We'd like to make all of those more flexible;
e.g., to support other persistence mechanisms / formats for the configuration data,
perhaps allow dynamic modifications to configuration values, support other
logging frameworks, etc.  These changes will probably require us to make the
service life cycle just a bit more complex, though, so we didn't tackle them
for the initial releases.

### Alternate implementations of the webserver service

We will soon provide a jetty 9 implementation of the web server service,
to leverage some performance gains and bug fixes that have landed in more
recent versions of Jetty.

We may also experiment with some other options such as Netty.

### Move default logging initialization from `log4j` to `logback`

We plan to switch the built-in logging initialization off of `log4j` and on to
`logback` at some point soon.  Services should not be affected by this, as
`clojure.tools/logging` should work transparently with either.

### Add support for other types of web applications

The current `:webserver-service` interface only provides a function for registering
a [Ring](https://github.com/ring-clojure/ring) application.  We'd like to add
a few more similar functions that would allow you to register other types of
web applications; specifically, an `add-servlet-handler` function that would
allow you to register any web application that conforms to the Java Servlet API,
and perhaps an `add-rack-handler` function that would allow you to register
a Rack application (to be run via JRuby).

### More robust life cycle / context management

We have been considering several options around managing the life cycles of
services, and potentially providing a context map that services could use to
tuck away some state information.  This would probably end up looking a bit
like the [Component Lifecycle from the Jig project](https://github.com/juxt/jig#components).

In addition to providing a bit more granularity for service initialization, it'd
also allow a more REPL-friendly workflow since the context object could be used
to introspect or restart subsystems of the application.  It should also make
it a lot easier for us to make the current hard-coded configuration service and
logging initilization pluggable.

We decided that this introduced too much complexity for our initial release, but
it's something we're likely to revisit soon.

## License

Copyright © 2013 Puppet Labs

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
