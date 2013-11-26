# Simple Web Service Example #

This example demonstrates how to create a simple set of web services which both depend upon a hit counter service for
generating content. When run, this code will attach two endpoints, `/bert` and `/ernie` which will generate a simple
block of HTML that displays seperate hit counters for each service.

All code needed to execute this example is located in `src/examples/ring_app`. The Clojure code is
contained in the `example_services.clj` file.

## Define the _hit count_ service ##

First we will need to define the hit counter service, which will later be used by the web services to show users which
visitor number they are. It is entirely expressed with this code:

```clojure
(def ^{:private true} hit-count (atom {}))

(defn- inc-and-get
  "Increments the hit count for the provided endpoint and returns the new hit count."
  [endpoint]
  {:pre [(string? endpoint)]
   :post [(integer? %) (> % 0)]}

  (let [new-hit-counts (swap! hit-count #(assoc % endpoint (cond (contains? % endpoint)
                                                             (inc (% endpoint)) :else 1)))]

    (log/debug "Incrementing hit count for" endpoint "from"
               (dec (new-hit-counts endpoint)) "to" (new-hit-counts endpoint))

    (new-hit-counts endpoint)))

(defservice count-service
  "This is a simple service which simply keeps a counter. It contains one function, inc-and-get, which
   increments the count and returns it."

  ;; This map declares the service's dependencies on other services and their functions,
  {:depends []              ; A :depends key is required to be present, even if it is empty.
   :provides [inc-and-get]} ; This service provides a function called inc-and-get.

  ;; Export the inc-and-get function via the return map.
  {:inc-and-get inc-and-get})
```

The `defservice` macro is used to define a _TrapperKeeper_ service and it is located in the
`puppetlabs.trapperkeeper.core` namespace.

After an optional doc-string, the first form needs to be a map containing a `:depends` key and a `:provides` key. These
are used to inform _TrapperKeeper_ of a service's dependencies and the functions publicly available for consumption by
other services. Since this service has no dependencies, an empty list is provided for the `:depends` key. The
`:provides` key is a list of a functions this service provides.

The `inc-and-get` function will keep a tally of hit counts for a provided endpoint. It
is later exported with the last form in the service definition which is a map of this service's function names to the
actual functions which do all the work. 

## Define the _bert_ service ##

The `bert-service` is a more interesting service which utilizes the `webserver` service to create HTTP
responses to requests made to specific endpoints, and is defined here:

```clojure
(defn- success-response
  "Return a ring response map containing a HTTP response code of 200 (OK) and HTML which displays the hitcount on this
   endpoint as well as all the data provided by Ring."
  [hit-count req]
  {:status 200
   :body (str "<h1>Hello from http://" (:server-name req) ":" (:server-port req) (:uri req) "</h1>"
              "<p>You are visitor number " hit-count ".</p>"
              "<pre>" (pprint-to-string req) "</pre>")})

(defn- ring-handler
  "Executes the inc-and-get command and passes it into success-reponse which generates a ring response."
  [inc-and-get endpoint req]
  (success-response (inc-and-get endpoint) req))

(defservice bert-service
  "This is the bert web service. The Clojure web application library, Ring, is used to create simple
   responses to an endpoint. It depends on the count-service above to use as a primitive hit counter.
   See https://github.com/ring-clojure/ring for documentation on Ring."

  ;; This service needs functionality from the webserver-service, and the count service.
  {:depends [[:webserver-service add-ring-handler]
             [:count-service inc-and-get]]
   ;; This service provides a shutdown function.
   :provides [shutdown]}

  (let [endpoint "/bert"]
    (add-ring-handler (partial ring-handler inc-and-get endpoint) endpoint)
    ;; Return the service's exposed function map.
    {:shutdown #(println "Bert service shutting down")}))
```

The general structure of this service is similar to the _hit count_ service; it contains a list of dependencies and
providers at the top and a map of provided functions at the bottom but this one has more content in the middle, which is
where service-specific code generally resides.

Since this service requires the use of functionality from other services, the `:depends` key contains a list of two
dependent services and the functions that are required from each. The element containing
`[:webserver-service add-ring-handler]` states that the `add-ring-handler` function from the
`:webserver-service` is needed by this service. And, of course, we also need to pull in the `inc-and-get` function
from the _hit count_ service previously defined. This is accomplished by the `[:count-service inc-and-get]` dependency
list item.

### Ring handlers ###

In the body of the service definition is a call to the `add-ring-handler` function. This function takes two
parameters, the first being a _ring handler_ which is, essentially, a function which takes a `request` data map as a
single parameter and returns a map containing different parts of an HTTP response.  The second parameter to
`add-ring-handler` is the base endpoint that the handler is attached to.

In this example, a partial function is created from the `ring-handler` function which is passed an endpoint to operate
on and the `inc-and-get` function from the _hit count_ service which generates the hit count.

See https://github.com/ring-clojure/ring for further documentation on the Ring API.

## Define the _ernie_ service ##

The _ernie_ service is nearly an exact copy of the _bert_ servce, but the endpoint has been changed.

```clojure
(defservice ernie-service
  "This is the ernie service which operates on the /ernie endpoint. It is essentially identical to the bert service."

  {:depends [[:webserver-service add-ring-handler]
             [:count-service inc-and-get]]
   :provides [shutdown]}

  (let [endpoint "/ernie"]
    (add-ring-handler (partial ring-handler inc-and-get endpoint) endpoint)
    {:shutdown #(println "Ernie service shutting down") }))
```


## Launching TrapperKeeper and running the app ##

To start up _TrapperKeeper_ and launch the previously defined services, use the following _lein_ command while in the
_TrapperKeeper_ home directory:

```sh
lein trampoline run -m puppetlabs.trapperkeeper.main --config src/examples/ring_app/config.ini \
                                                     --bootstrap-config src/examples/ring_app/bootstrap.cfg
```

Once _TrapperKeeper_ is running, point your browser to either http://localhost:8080/ernie or http://localhost:8080/bert
to see the ring handlers and hit counter in action.

As you can see from the command line there are two configuration files needed to launch _TrapperKeeper_. 

### The `bootstrap.cfg` file ###

The bootstrap config file contains a list of services that _TrapperKeeper_ will load up and make available. They are
listed as fully-qualified Clojure namespaces and service names. For this example the bootstrap.cfg looks like this:

```
puppetlabs.trapperkeeper.services.jetty.jetty-service/webserver-service
examples.ring-app.example-services/count-service
examples.ring-app.example-services/bert-service
examples.ring-app.example-services/ernie-service
``` 

This configuration indicates that _TrapperKeeper's_ bundled webserver-service is to be loaded, as well as the three new
services defined in the `example_services.clj` file.

### The `config.ini` configuration file ###

For the application configuration, a file called `config.ini` provides the most minimal configuration of the
webserver-service, which is simply the port the service will be listening on and also a `logging-config` key which
contains a path to a `log4j.properties` file which defines the logging configuration.

```ini
[global]
# Points to a log4j properties file
logging-config = src/examples/ring_app/log4j.properties

[webserver]
# Port to listen on for clear-text HTTP.
port = 8080
```

### The `log4j.properties` file ###

A minimal `log4j.properties` file is provided in this example to demonstrate how to configure logging in
_TrapperKeeper_.

```properties
log4j.rootLogger=INFO, A1
log4j.appender.A1=org.apache.log4j.ConsoleAppender
log4j.appender.A1.layout=org.apache.log4j.PatternLayout
log4j.appender.A1.layout.ConversionPattern=%d %-5p [%c{2}] %m%n
```

See http://logging.apache.org/log4j/1.2/manual.html for documentation on how to configure log4j.

### Debug mode ###

There is a debugging statement inside the count-service which displays the state of the counter when it is
to be incremented. To turn on debugging logging pass in the `--debug` option on the command line, like so:

```sh
lein trampoline run -m puppetlabs.trapperkeeper.main --config src/examples/ring_app/config.ini \
                                                     --bootstrap-config src/examples/ring_app/bootstrap.cfg \
                                                     --debug
```

When run you will see debug output any time you hit the hit-counting endpoint. This is the equivalent of setting the
`log4j.rootLogger` to `DEBUG` instead of `INFO`, and will override whatever log level the root logger is set to.