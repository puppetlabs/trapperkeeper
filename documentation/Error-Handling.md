# Error Handling

## Errors During `init` or `start`

If the `init` or `start` function of any service throws a `Throwable`, it will cause Trapperkeeper to shut down.  No further `init` or `start` functions of any services will be called after the first `Throwable` is thrown.  If you are using Trapperkeeper's `main` function, all service `stop` functions will be called before the process terminates.  The `stop` functions are called in order to give each service a chance to clean up any resources which may have only been partially initialized before the `Throwable` was thrown -- e.g., allowing any worker threads which may have been spawned to be gracefully shut down so that the process can terminate.  Service `stop` functions must be designed such that they could be executed with no adverse effects even if called before the service's `init` and `start` functions could successfully complete.

If the `init` or `start` function of your service launches a background thread to perform some costly initialization computations (like, say, populating a pool of objects which are expensive to create), it is advisable to wrap that computation inside a call to `shutdown-on-error`; however, you should note that `shutdown-on-error` does *not* short-circuit Trapperkeeper's start-up sequence - the app will continue booting.  The `init` and `start` functions of all services will still be run, and once that has completed, all `stop` functions will be called, and the process will terminate.

If the exception thrown by `init` or `start` is an `ex-info` exception
containing the same kind of map that
[`request-shutdown`](Built-in-Shutdown-Service.md#request-shutdown)
accepts, then Trapperkeeper will print the specified messages and exit
with the specified status as described there.  For example:

    (ex-info ""
             {:kind :puppetlabs.trapperkepper.core/exit`
              {:status 3
               :messages [["Unexpected filesystem error ..." *err*]]}})

The `ex-info` message string is currently ignored.

## Services Should Fail Fast

Trapperkeeper embraces fail-fast behavior.  With that in mind, we advise writing services that also fail-fast.  In particular, if your service needs to spin-off a background thread to perform some expensive initialization logic, it is a best practice to push as much code as possible outside of the background thread (for example, validating configuration data), because `Throwables` on the main thread will propagate out of `init` or `start` and cause the application to shut down - i.e., it will *fail fast*.  There are different operational semantics for errors thrown on a background thread (see previous section).
