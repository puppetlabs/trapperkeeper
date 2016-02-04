(ns puppetlabs.trapperkeeper.app
  (:require [schema.core :as schema]
            [puppetlabs.trapperkeeper.services :as s])
  (:import (clojure.lang Agent IDeref)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schema

(def TrapperkeeperAppOrderedServices
  [[(schema/one schema/Keyword "service-id")
    (schema/one (schema/protocol s/Service) "Service")]])

(def TrapperkeeperAppContext
  {:service-contexts {schema/Keyword {schema/Any schema/Any}}
   :ordered-services TrapperkeeperAppOrderedServices
   :services-by-id {schema/Keyword (schema/protocol s/Service)}
   :lifecycle-agent Agent
   :shutdown-reason-promise IDeref})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; App Protocol

(defprotocol TrapperkeeperApp
  "Functions available on a trapperkeeper application instance"
  (get-service [this service-id] "Returns the service with the given service id")
  (service-graph [this] "Returns the prismatic graph of service fns for this app")
  (app-context [this] "Returns the application context for this app (an atom containing a map)")
  (check-for-errors! [this] (str "Check for any errors which have occurred in "
                                 "the bootstrap process.  If any have "
                                 "occurred, throw a `java.lang.Throwable` with "
                                 "the contents of the error.  If none have "
                                 "occurred, return the input parameter."))
  (init [this] "Initialize the services")
  (start [this] "Start the services")
  (stop [this] "Stop the services")
  (restart [this] "Stop and restart the services"))