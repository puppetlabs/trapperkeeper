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


