## Service Interfaces

One of the goals of Trapperkeeper's "service" model is that a service should be thought of as simply an interface; any given service provides a protocol as its "contract", and the implementation details of these functions are not important to consumers.  (This borrows heavily from OSGi's concept of a "service".)  This means that you can write multiple implementations of a given service and swap them in and out of your application by simply modifying your configuration, without having to change any of the consuming code.  The Trapperkeeper `webserver` service is an example of this pattern; we provide both a [Jetty 7 webserver service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty7) and a [Jetty 9 webserver service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9) that can be used interchangeably.

One of the motivations behind this approach is to make it easier to ship "on-premise" or "shrink-wrapped" software written in Clojure.  In SaaS environments, the developers and administrators have tight control over what components are used in an application, and can afford to be fairly rigid about how things are deployed.  For on-premise software, the end user may need to have a great deal more control over how components are mixed and matched to provide a solution that scales to meet their needs; for example, a small shop may be able to run 10 services on a single machine without approaching the load capacity of the hardware, but a slightly larger shop might need to separate those services out onto multiple machines.  Trapperkeeper provides an easy way to do this at packaging time or configuration time, and the administrator does not necessarily have to be familiar with clojure or EDN in order to effectively configure their system.

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

Given this combination of services, you might have a `bootstrap.cfg` file that looks like:

<pre>
services.foo-consumer/foo-consumer
services.foo.<strong>lowercase-foo</strong>/foo-service
</pre>

If you then ran your app, calling the function `bar` provided by the `foo-consumer` service would yield: `"Foo service returned 'foo'"`.  If you then modified your `bootstrap.cfg` file to look like:

<pre>
services.foo-consumer/foo-consumer
services.foo.<strong>uppercase-foo</strong>/foo-service
</pre>

Then the `bar` function would return `"Foo service returned 'bar'"`.  This allows you to swap out a service implementation without making any code changes; you need only modify your `bootstrap.cfg` file.

This is obviously a trivial example, but the same approach could be used to swap out the implementation of something more interesting; a webserver, a message queue, a persistence layer, etc.  This also has the added benefit of helping to keep code more modular; a downstream service should only interact with a service that it depends on through a well-known interface.
