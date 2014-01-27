(ns puppetlabs.trapperkeeper.app)

(defprotocol TrapperkeeperApp
  "Functions available on a trapperkeeper application instance"
  (get-service [this service-id] "Returns the service with the given service id")
  (service-graph [this] "Returns the prismatic graph of service fns for this app")
  (app-context [this] "Returns the application context for this app (an atom containing a map)")
  (init [this] "Initialize the services")
  (start [this] "Start the services")
  (stop [this] "Stop the services"))