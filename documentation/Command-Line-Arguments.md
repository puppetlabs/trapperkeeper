# Command Line Arguments

Trapperkeeper's default mode of operation is to handle the processing of application command-line arguments for you.  This is done for a few reasons:

* It needs some data for bootstrapping
* Since the idea is that you will be composing multiple services together in a Trapperkeeper instance, managing command line options across multiple services can be tricky; using the configuration service is easier
* Who wants to process command-line arguments, anyway?

Note that if you absolutely need control over the command line argument processing, it is possible to circumvent the built-in handling by calling Trapperkeeper's `bootstrap` function directly; see additional details in the [Bootstrapping](Bootstrapping) page.

Trapperkeeper supports three command-line arguments:

* `--config/-c`: The path to the configuration file or directory. This option is used to initialize the configuration service. This argument is optional; if not specified, Trapperkeeper will act as if you had given it an empty configuration file.
* `--bootstrap-config/-b`: This argument is optional; if specified, the value should be a path to a bootstrap configuration file that Trapperkeeper will use (instead of looking for `bootstrap.cfg` in the current working directory or on the classpath)
* `--debug/-d`: This option is not required; it's a flag, so it will evaluate to a boolean.  If `true`, sets the logging level to DEBUG, and also sets the `:debug` key in the configuration map provided by the configuration-service.

## `main` and Trapperkeeper

There are three different ways that you can initiate Trapperkeeper's bootstrapping process:

### Defer to Trapperkeeper's `main` function

In your Leiningen project file, you can simply specify Trapperkeeper's `main` as your `:main`:

    :main puppetlabs.trapperkeeper.main

Then you can simply use `lein run --config ...` to launch your app, or `lein uberjar` to build an executable jar file that calls Trapperkeeper's `main`.

### Call Trapperkeeper's `main` function from your code

If you don't want to defer to Trapperkeeper as your `:main` namespace, you can simply call Trapperkeeper's `main` from your own code.  All that you need to do is to pass along the command line arguments, which Trapperkeeper needs for initializing bootstrapping, configuration, etc.  Here's what that might look like:

```clj
(ns foo
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]))

(defn -main
  [& args]
  ;; ... any code you like goes here
  (apply trapperkeeper/main args))
```

### Call Trapperkeeper's `run` function directly

If your application needs to handle command line arguments directly, rather than allowing Trapperkeeper to handle them, you can circumvent Trapperkeeper's `main` function and call `run` directly.

*NOTE* that if you intend to write multiple services and load them into the same Trapperkeeper instance, it can end up being tricky to deal with varying sets of command line options that are supported by the different services.  For this reason, it is generally preferable to configure the services via the configuration files and not rely on command-line arguments.

But, if you absolutely must...  Here's how it can be done:

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

Note that Trapperkeeper's `run` function requires a map as an argument, and this map must contain the `:config` key which Trapperkeeper will use just as it would have used the `--config` value from the command line.  You may also (optionally) provide `:bootstrap-config` and `:debug` keys, to override the path to the bootstrap configuration file and/or enable debugging on the application.

### Other Ways to Boot

We use the term `boot` to describe the process of building up an instance of a `TrapperkeeperApp`, and then calling `init` and `start` on all of its services in the correct order.

It is possible to use the Trapperkeeper framework at a slightly lower level.  Using `run` or `main` will boot all of the services and then block the main thread until a shutdown is triggered; if you need more control, you'll be getting a reference to a `TrapperkeeperApp` directly.

#### `TrapperkeeperApp` protocol

There is a protocol that represents a Trapperkeeper application:

```clj
(defprotocol TrapperkeeperApp
  "Functions available on a Trapperkeeper application instance"
  (app-context [this] "Returns the application context for this app (an atom containing a map)")
  (check-for-errors! [this] (str "Check for any errors which have occurred in "
                                   "the bootstrap process.  If any have "
                                   "occurred, throw a `java.lang.Throwable` with "
                                   "the contents of the error.  If none have "
                                   "occurred, return the input parameter.")
  (init [this] "Initialize the services")
  (start [this] "Start the services")
  (stop [this] "Stop the services"))
```

With a reference to a `TrapperkeeperApp`, you can gain more control over when the lifecycle functions are called.  To get an instance, you can call any of these functions:

* `(boot-with-cli-data [cli-data])`: this function expects you to process your own command-line arguments into a map (as with `run`).  It then creates a TrapperkeeperApp, boots all of the services, and returns the app.
* `(boot-services-with-cli-data [services cli-data])`: this function expects you to process your own command-line arguments into a map, and also to build up your own list of services to pass in as the first argument.  It circumvents the normal Trapperkeeper `bootstrap.cfg` process, creates a `TrapperkeeperApp` with all of your services, boots them, and returns the app.
* `(boot-services-with-config [services config])`: this function expects you to process your own command-line arguments, configuration data, and build up your own list of services.  You pass it the list of services and the map of all service configuration data, and it circumvents the normal `bootstrap.cfg` process, creates a `TrapperkeeperApp` with all of your services, boots them, and returns the app.

Each of the above gives you a way to get a reference to a `TrapperkeeperApp` without blocking the main thread to wait for shutdown.  If, later, you do wish to wait for the shutdown, you can simply call `run-app` and pass it your `TrapperkeeperApp`.  Alternately, you can call `stop` on the `TrapperkeeperApp` to initiate shutdown on your own terms.

Note that all of these functions *do* boot your services.  If you wish to have more control over the booting of the services, you can use this function:

* `(build-app [services config-data])`: this function creates a `TrapperkeeperApp` *without* booting the services.  You can then boot them yourself by calling `init` and `start` on the `TrapperkeeperApp`.
