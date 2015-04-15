## 1.1.1

This is a maintenance / minor feature release.

* [TK-197](https://tickets.puppetlabs.com/browse/TK-197) - update prismatic
  dependencies to latest versions.
* Add support for yaml config files
* [TK-131](https://tickets.puppetlabs.com/browse/TK131) Relax preconditions on logging configuration

## 1.1.0

This is a minor feature release.

* Add support for logback's `EvaluatorFilter`, which allows users to configure
  the logging to filter log messages based on regular expression patterns.

## 1.0.1

* Fix an issue wherein nothing would be logged to the console when the
  --debug flag was set

## 1.0.0

* Promoting previous release to 1.0.0, so that we can begin to be more deliberate
  about adhering to semver from here on out.

## 0.5.2

This is a minor feature and bugfix release.

* Call the `service-symbol` function in lifecycle error messages to make it easier
  to determine which service caused the error
* Fix an IllegalArgumentException that would occur when catching a slingshot exception
  in the TK `main` function.
* Allow multiple comma-separated config files and directories to be specified
  in the --config CLI argument.

## 0.5.1

This is a bugfix release.
* Fix a bug that prevented `defservice` from working with protocols that were defined in a different namespace.

## 0.5.0

This is a feature release with a minor breaking API change.

* The breaking API change affects the functions defined in the
  `puppetlabs.trapperkeeper.services/Service` protocol - namely, `service-context`.
  References to these functions are no longer automatically in scope inside a 
  `service` or `defservice` definition as they were previously (via macro magic),
  and they must be `require`d like any other function - 
  `(require '[puppetlabs.trapperkeeper.services :refer [service-context]])`.
* Changed schema version to support the Bool type
* Improve implementation of the `service` macro
* Formalize public function for loading config

## 0.4.3

This is a minor feature release.

* Moved documentation to github wiki
* Get rid of requirement for `--config` command-line argument
* Add new `service-symbol` and `get-services` functions to protocols
* Update dependencies

## 0.4.2

This is a minor feature release.

* Add a new configuration setting `middlewares` to the nREPL service, to allow
  registration of nREPL middleware (e.g. for compatibility with LightTable).
  (Thanks to `exi` for this contribution!)

## 0.4.1

This is a maintenance/bugfix release.

* Fix a minor bug in testutils/logging where we inadvertently changed the return value of
  log statements.
* Add an explicit call to `shutdown-agents` on trapperkeeper exit, to prevent the JVM from
  hanging for 60 seconds on shutdown (if any services were using `future`).

## 0.4.0

This release includes improved error handling and logic for shutting down Trapperkeeper applications.

* Improved handling of errors during a service's `init` or `start` functions:
  * All services' `stop` functions are now called, even when an error is thrown by any service's 
    `init` or `start` function.  This means that `stop` implementations must now be resilient
    to invocation even when `init` or `start` has not executed.
  * Updated `boot-services-with-cli-data`, `boot-services-with-config`, and `boot-with-cli-data`
    to return the `TrapperkeeperApp` instance rather than propagating the `Throwable`.
* Updated example "Reloaded" pattern usage to use the new `check-for-errors!` 
  function on the `TrapperkeeperApp` instance to detect any errors that may have occurred 
  while services were being bootstrapped.

## 0.3.12

This is a maintenance release.

* Upgrade fs dependency to 1.4.5 to standardize across projects

## 0.3.11

This is a maintenance/bugfix release.

* Fix minor bug in how nrepl service loads its configuration
* Add CONTRIBUTING.md file
* Fix a few misleading things in the README (dan@simple.com)

## 0.3.10

This is a maintenance release.

 * Update version number of kitchensink dependency to 0.6.0, to get rid of transitive dependencies on SSL libraries.

## 0.3.9

This is a maintenance release.

 * Update version number of logback dependency from 1.0.13 to 1.1.1, to resolve a bug in logback that was affecting our jetty9 web server.

## 0.3.8

This is a bugfix and maintenance release.

 * Improve logging of exceptions that occur during bootstrapping.

## 0.3.7

This is a bugfix and maintenance release.

 * Log exceptions that occur during bootstrapping.

## 0.3.6

This is a bugfix and maintenance release.

 * Move typesafe config code to an external library - https://github.com/puppetlabs/clj-typesafe-config
 * Improve error handling and logging in `shutdown-on-error`.
 
## 0.3.5
 * Improved error handling in the `service`/`defservice` macros.
 * Improved error handling in the shutdown logic, particularly when using `shutdown-on-error`.
 * Fix a bug that prevented `service-id` from being called from a service's `init` function.
 * Minor documentation fixes and improvements.

## 0.3.4
 * Add new macros in `testutils/bootstrap` namespace, to make it easier to write tests for services
 * Add support for .edn, .conf, .json, .properties config files in addition to .ini

## 0.3.3
 * Fix a bug in how we were handling command-line arguments in the case where the user does not pass any
 * Add a new function `get-service` to the `Service` protocol, which allows service authors to get a reference to the protocol instance of a service if they prefer that to the prismatic-style function injections

## 0.3.2
 * Bugfix release
 * Use prismatic schema validation during tests to ensure we are complying with
   our advertised schemas
 * Fix bug where we were not including lifecycle functions in the schema
 * Fix bug in error handling of prismatic exceptions
 * Upgrade to version 0.2.1 of prismatic schema, which includes a fix for
   some thing related to aot.

## 0.3.0
 * Changes to `defservice` API so that it supports service lifecycles more explicitly,
   and now uses clojure protocols as the means for specifying functions provided
   by a service.
 * Upgrade to 0.5.1 of kitchensink, which includes a significant performance
   improvement for applications that are accepting HTTPS connections


