## 0.3.2
 * Fix a bug in how we were handling command-line arguments in the case where the user does not pass any
 * Add a new function `get-service` to the `Service` protocol, which allows service authors to get a reference to the protocol instance of a service if they prefer that to the prismatic-style function injections

## 0.3.1
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


