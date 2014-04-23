(ns puppetlabs.trapperkeeper.app)

(defprotocol TrapperkeeperApp
  "Functions available on a trapperkeeper application instance"
  (get-service [this service-id] "Returns the service with the given service id")
  (service-graph [this] "Returns the prismatic graph of service fns for this app")
  (app-context [this] "Returns the application context for this app (an atom containing a map)")
  (check-for-errors! [this] (str "Check for any errors which have occurred in "
                                 "the bootstrap process.  If any have "
                                 "occurred, throw a `java.lang.Throwable` with "
                                 "the contents of the error."))
  (init [this] "Initialize the services")
  (start [this] "Start the services")
  (stop [this] "Stop the services"))