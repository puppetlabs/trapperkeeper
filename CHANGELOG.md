## 3.2.0-SNAPSHOT

This is a minor feature release

* Backward compatible changes to the signature of `puppetlabs.trapperkeeper.internal/shutdown!` function. Returns collection of exceptions caught during execution of shutdown sequence instead of nil.
* Extend `stop` method of `puppetlabs.trapperkeeper.app/TrapperkeeperApp` protocol with an argument `throw?` to handle cases where exceptions in shutdown sequence should be rethrown.
* Change default behavior of `puppetlabs.trapperkeeper.testutils.bootstrap` helper macroses to throw exception when shutdown finished abruptly.

## 3.1.1

This is a maintenance release

* Updates to current clj-parent

## 3.1.0

This is a minor feature release

* [PDB-4636](https://github.com/puppetlabs/trapperkeeper/pull/287) - support custom exit status/messages

## 3.0.0

This is a maintenance release

* Updates to current clj-parent to clean up project.clj and update dependencies
* Tests changes for readability and compatibility with Java11

## 2.0.1

This is a maintenance release

* Ensures that all errors are correctly thrown, notably errors about bad config schemas.

## 2.0.0

This is a maintenance release

* [ORCH-2282](https://tickets.puppetlabs.com/browse/ORCH-2282) - Updates to current clj-parent
to support using nrepl/nrepl
* Updates required for using nrepl/nrepl; mainline development for nrepl moved from
org.clojure/tools.nrepl as of the 0.3.x series (last on this line was 0.2.13)
* Updating to this version of trapperkeeper requires lein >=2.9.0 (:min-lein-version updated)
* Drops support for JDK7

## 1.5.6

This is a maintenance release

* [TK-466](https://tickets.puppetlabs.com/browse/TK-466) - Log SIGHUP events at INFO level

## 1.5.5

This is a maintenance release

* Fix log message accidentally converted to a warning

## 1.5.4

This is a maintenance release.

* Fix adding to classpath under Java 9

## 1.5.3

This is a maintenance release.

* [TK-411](https://tickets.puppetlabs.com/browse/TK-411) - Externalize strings for i18n
* [TK-439](https://tickets.puppetlabs.com/browse/TK-439) - Handle exceptions having no
  type key in main
* Fix symbol redef warnings under Clojure 1.9
* Improved lifecycle debug logging

## 1.5.2

This is a maintenance release.

* [SERVER-1494](https://tickets.puppetlabs.com/browse/SERVER-1494) - use `lein-parent`
  plugin to inherit dependency versions from parent project.

## 1.5.1

This is a minor feature release

* [TK-405](https://tickets.puppetlabs.com/browse/TK-405) - Add support for
  specifying the restart file option via a command line argument

## 1.5.0

This is a feature/bugfix/maintenance release

* [TK-345](https://tickets.puppetlabs.com/browse/TK-345) - Add support for optional
  restart file which, if specified, will contain an integer that increments when
  a TK app has successfully started all of its services
* [TK-382](https://tickets.puppetlabs.com/browse/TK-382) - Fix bug where optional
  dependencies could not be specified for a service without a protocol
* [TK-397](https://tickets.puppetlabs.com/browse/TK-397) - Update to logback 1.1.7

## 1.4.1

This is a bugfix release. It fixes a single issue

* [TK-375](https://tickets.puppetlabs.com/browse/TK-375) - Regression in 1.4.0
  when loading bootstrap.cfg from resources/classpath

## 1.4.0

This is feature/bugfix release. It is a re-release of 1.3.2

* [TK-347](https://tickets.puppetlabs.com/browse/TK-347) - Support directories
  and paths in TK's "bootstrap-config" CLI argument
* [TK-211](https://tickets.puppetlabs.com/browse/TK-211) - Trapperkeeper
  doesn't error if two services implementing the same protocol are started
* [TK-349](https://tickets.puppetlabs.com/browse/TK-349) - TK should not
  fail during startup if an unrecognized service is found in bootstrap config
* [TK-351](https://tickets.puppetlabs.com/browse/TK-351) - Ensure all bootstrap
  related errors log what file they come from

## 1.3.2

This version was released by mistake, it was intended to be 1.4.0


## 1.3.1

This is a bugfix / maintenance / minor feature release

* [TK-319](https://tickets.puppetlabs.com/browse/TK-319) - fix a bug where
  optional dependencies could not be used without a service protocol
* [TK-325](https://tickets.puppetlabs.com/browse/TK-325) - move documentation
  into repo, instead of storing it on the github wiki
* [HC-51](https://tickets.puppetlabs.com/browse/HC-51) - update to newer
  version of clj typesafe / hocon wrapper, fixing bug that prevented
  variable interpolation from working properly in hocon config files
* New `bootstrap-services-with-config` testutils macro
* [TK-342](https://tickets.puppetlabs.com/browse/TK-342) - new logging
  testutils macros, e.g. `with-logged-event-maps`.
* [TK-326](https://tickets.puppetlabs.com/browse/TK-326),
  [TK-330](https://tickets.puppetlabs.com/browse/TK-330),
  [TK-331](https://tickets.puppetlabs.com/browse/TK-331) - various minor
  improvements to HUP support to eliminate some bugs/annoyances that
  were possible in pathological situations

## 1.3.0

This is a feature release.

* [TK-202](https://tickets.puppetlabs.com/browse/TK-202) - adds support for
  restarting a TK app via HUP signal, w/o shutting down entire JVM process
* [TK-315](https://tickets.puppetlabs.com/browse/TK-315) - update raynes.fs
  dependency to 1.4.6, to minimize dependency conflicts for consumers
* RELEASE NOTE: adds a dependency on core.async
* RELEASE NOTE: minor changes to internal `app-context` API; all service
  contexts are now stored under a key called `:service-contexts`.  This
  shouldn't affect any consuming code unless you were digging into the
  internal `app-context` API for really low-level tests or similar.

## 1.2.0

This is a minor feature release.

* [TK-299](https://tickets.puppetlabs.com/browse/TK-299) - support optional
  dependencies, which allow services to take advantage of other services if
  they're included in the bootstrap and gracefully handled when they are not
  included. See the
  [docs](https://github.com/puppetlabs/trapperkeeper/wiki/Defining-Services#optional-services)
  for more detail.
* Use newer version of schema library and make use of more schemas.

## 1.1.3

This is a bugfix release.

* [TK-311](https://tickets.puppetlabs.com/browse/TK-311) - fix a minor bug in the new
  logging testutils, where the log appenders weren't implementing the `isStarted`
  method.

## 1.1.2

This is a bugfix / minor feature release.

* Various, significant improvements to logging testutils, courtesy of Rob Browning.
* [TK-291](https://tickets.puppetlabs.com/browse/TK-291) - `(is (logged?` test assertion
  now captures log messages that were logged by other (non-Clojure) threads.
* `logs-matching` now has an additional signature that accepts a log level 
* Improvements to error handling when an error occurs in TK's `main` function

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


