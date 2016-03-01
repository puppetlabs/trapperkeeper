# Overview

Trapperkeeper is a Clojure framework for hosting long-running applications and services.  You can think of it as a "binder", of sorts--for Ring applications and other modular bits of Clojure code.

It ties together a few nice patterns we've come across in the clojure community:

* Stuart Sierra's ["reloaded" workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded)
* Component lifecycles (["Component"](https://github.com/stuartsierra/component), ["jig"](https://github.com/juxt/jig#components))
* [Composable services](http://plumatic.github.io/graph-abstractions-for-structured-computation/) (based on the excellent [Plumamatic graph library](https://github.com/plumatic/plumbing))

We also had a few other needs that Trapperkeeper addresses (some of these arise because of the fact that we at Puppet Labs are shipping on-premises software, rather than SaaS.  The framework is a shipping part of the application, in addition to providing useful features for development):

* Well-defined service interfaces (using clojure protocols)
* Ability to turn services on and off via configuration after deploy
* Ability to swap service implementations via configuration after deploy
* Ability to load multiple web apps (usually Ring) into a single webserver
* Unified initialization of logging and configuration so services don't have to concern themselves with the implementation details
* Super-simple configuration syntax

A "service" in Trapperkeeper is represented as simply a map of clojure functions.  Each service can advertise the functions that it provides via a protocol, as well as list other services that it has a dependency on.  You then configure Trapperkeeper with a list of services to run and launch it.  At startup, it validates that all of the dependencies are met and fails fast if they are not.  If they are, then it injects the dependency functions into each service and starts them all up in the correct order.

Trapperkeeper provides a few built-in services such as a configuration service, a shutdown service, and an nREPL service.  Other services (such as a web server service) are available and ready to use, but don't ship with the base framework.  Your custom services can specify dependencies on these and leverage the functions that they provide.  For more details, see the [Built-in Services](Built-in-Services.md) page.

# Credits and Origins

Most of the heavy-lifting of the Trapperkeeper framework is handled by the excellent [Plumatic Graph](https://github.com/plumatic/plumbing) library.  To a large degree, Trapperkeeper just wraps some basic conventions and convenience functions around that library, so many thanks go out to the fine folks at Plumatic for sharing their code!

Trapperkeeper borrows some of the most basic concepts of the OSGi "service registry" to allow users to create simple "services" and bind them together in a single container, but it doesn't attempt to do any fancy classloading magic, hot-swapping of code at runtime, or any of the other things that can make OSGi and other similar application frameworks complex to work with.

# Hopes and Dreams

Here are some ideas that we've had and things we've played around with a bit for improving Trapperkeeper in the future.

## More flexible configuration service

The current configuration service is hard-coded to use files (`.ini`, `.edn`, `.conf`, `.json`, or `.properties`) as its back end and is hard-coded to use `logback` to initialize logging.  We'd like to make all of those more flexible; e.g., to support other persistence mechanisms, perhaps allow dynamic modifications to configuration values, support other logging frameworks, etc.  These changes will probably require us to make the service life cycle just a bit more complex, though, so we didn't tackle them for the initial releases.

## Alternate implementations of the webserver service

We currently provide both a [Jetty 7](https://github.com/puppetlabs/trapperkeeper-webserver-jetty7) and a [Jetty 9](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9) implementation of the web server service.  We may also experiment with some other options such as Netty.

## Add support for other types of web applications

The current `:webserver-service` interface provides functions for registering a [Ring](https://github.com/ring-clojure/ring) or [Servlet](http://docs.oracle.com/javaee/7/api/javax/servlet/Servlet.html) application.  We'd like to add a few more similar functions that would allow you to register other types of web applications, specifically an `add-rack-handler` function that would allow you to register a Rack application (to be run via JRuby).
