;;;; Application Shutdown Support
;;;;
;;;; Provides top-level functions to be called by TrapperKeeper internally
;;;; and a shutdown service for application services to utilize.
;;;;
;;;; Performing the actual shutdown is relatively easy, but dealing with
;;;; exceptions during shutdown and providing various ways for services
;;;; to trigger shutdown make up most of the code in this namespace.
;;;;
;;;; During development of this namespace we wanted to ensure that the
;;;; shutdown sequence occurred on the main thread and not on some
;;;; service's worker thread. Because of this, a Clojure `promise` is
;;;; used to pass contextual shutdown information back to the main thread,
;;;; at which point it is responsible for calling back into this namespace
;;;; to perform the appropriate shutdown steps.
;;;;
;;;; As a consequence of the implementation, the main blocking behavior of
;;;; TrapperKeeper is defined here by `wait-for-shutdown`. This function
;;;; simply attempts to dereference the above mentioned promise, which
;;;; until it is realized (e.g. by a call to `deliver`) will block, and the
;;;; delivered value returned. This value will contain contextual information
;;;; regarding the cause of the shutdown, and is intended to be passed back
;;;; in to the top-level functions that perform various shutdown steps.
(ns puppetlabs.trapperkeeper.shutdown
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :refer [add-shutdown-hook! boolean?]]
            [puppetlabs.trapperkeeper.app :refer [walk-leaves-and-path]]
            [puppetlabs.trapperkeeper.services :refer [service get-service-fn]]
            [plumbing.fnk.pfnk :refer [input-schema output-schema fn->fnk]])
  (:import (puppetlabs.trapperkeeper.app TrapperKeeperApp)))

(def ^{:private true
       :doc "The possible causes for shutdown to be initiated."}
  shutdown-causes #{:requested :service-error :jvm-shutdown-hook})

(def ^{:private true
       :doc "List of service shutdown hooks in service-dependency order."}
  ;; Note that we maintain this list of shutdown functions as
  ;; opposed to walking the graph because there's no `walk`
  ;; function in the Prismatic library that guarantees traversal
  ;; in dependency order.
  shutdown-fns (atom ()))

(defn- request-shutdown
  "Initiate the normal shutdown of TrapperKeeper. This is asynchronous.
  It is assumed that `wait-for-shutdown` has been called and is blocking.
  Intended to be used by application services (likely their worker threads)
  to programatically trigger application shutdown.
  Note that this is exposed via the `shutdown-service` where it is a no-arg
  function."
  [shutdown-reason-promise]
  (deliver shutdown-reason-promise {:cause :requested}))

(defn- shutdown-on-error
  "A higher-order function that is intended to be used as a wrapper around
  some logic `f` in your services. It will wrap your application logic in a
  `try/catch` block that will cause TrapperKeeper to initiate an error shutdown
  if an exception occurs in your block. This is generally intended to be used
  on worker threads that your service may launch.

  If an optional `on-error-fn` is provided, it will be executed if `f` throws
  an exception, but before the primary shutdown sequence begins."
  ([shutdown-reason-promise f]
    (shutdown-on-error shutdown-reason-promise f nil))
  ([shutdown-reason-promise f on-error-fn]
    {:pre [(ifn? f)
           ((some-fn nil? ifn?) on-error-fn)]}
    (try
      (f)
      (catch Exception e
        (deliver shutdown-reason-promise {:cause       :service-error
                                          :error       e
                                          :on-error-fn on-error-fn})))))

(defn- shutdown-service
  "Provides various functions for triggering application shutdown programatically.
  Primarily intended to serve application services, though TrapperKeeper also uses
  this service internally and therefore is always available in the application graph.

  Provides:
  * :request-shutdown  - Asynchronously trigger normal shutdown
  * :shutdown-on-error - Higher-order function to execute application logic
                         and trigger shutdown in the event of an exception

  For more information, see `request-shutdown` and `shutdown-on-error`."
  [shutdown-reason-promise]
  ((service :shutdown-service
     {:depends  []
      :provides [wait-for-shutdown request-shutdown shutdown-on-error]}
     {:wait-for-shutdown  #(deref shutdown-reason-promise)
      :request-shutdown   (partial request-shutdown shutdown-reason-promise)
      :shutdown-on-error  (partial shutdown-on-error shutdown-reason-promise)})))

(defn shutdown!
  "Perform shutdown on the application by calling all service shutdown hooks.
  Services will be shut down in dependency order."
  []
  (log/info "Beginning shutdown sequence")
  (doseq [f @shutdown-fns]
    (try
      (f)
      (catch Exception e
        (log/error e "Encountered error during shutdown sequence"))))
  (log/info "Finished shutdown sequence"))

(defn- grab-shutdown-functions!
  "Given a path to a service in the graph, extract the shutdown function from
  the service and add it to the `shutdown-fns` list atom."
  [path orig-fnk]
  {:pre  [(sequential? path)
          (ifn? orig-fnk)]
   :post [(ifn? %)]}
  (let [in  (input-schema orig-fnk)
        out (output-schema orig-fnk)
        f   (fn [injected-vals]
              (let [result (orig-fnk injected-vals)]
                (when-let [shutdown-fn (result :shutdown)]
                  (swap! shutdown-fns conj shutdown-fn))
                result))]
    (fn->fnk f [in out])))

(defn register-shutdown-hooks!
  "Walk the graph and register all shutdown functions. The functions
  will be called when the JVM shuts down, or by calling `shutdown!`."
  [graph]
  (let [wrapped-graph           (walk-leaves-and-path grab-shutdown-functions! graph)
        shutdown-reason-promise (promise)
        shutdown-service        (shutdown-service shutdown-reason-promise)]
    (add-shutdown-hook! #(when-not (realized? shutdown-reason-promise)
                           (shutdown!)
                           (deliver shutdown-reason-promise {:cause :jvm-shutdown-hook})))
    (merge shutdown-service wrapped-graph)))

(defn wait-for-shutdown
  "Wait for shutdown to be initiated - either externally (such as Ctrl-C) or
  internally (requested by service or service error) - and return the reason.

  The reason map may contain the following keys:
  * :cause       - One of :requested, :service-error, or :jvm-shutdown-hook
  * :error       - The error associated with the :service-error cause
  * :on-error-fn - An optional error callback associated with the :service-error cause"
  [^TrapperKeeperApp app]
  {:post [(map? %)]}
  ((get-service-fn app :shutdown-service :wait-for-shutdown)))

(defn initiated-internally?
  "Given the shutdown reason obtained from `wait-for-shutdown`, determine whether
  the cause was internal so further shutdown steps can be performed.
  In the case of an externally-initiated shutdown (e.g. Ctrl-C), we assume that
  the JVM shutdown hook will perform the actual shutdown sequence as we don't want
  to rely on control returning to the main thread where it can perform shutdown."
  [shutdown-reason]
  {:pre  [(map? shutdown-reason)]
   :post [(boolean? %)]}
  (contains? (disj shutdown-causes :jvm-shutdown-hook) (:cause shutdown-reason)))

(defn call-error-handler!
  "Given the shutdown reason obtained from `wait-for-shutdown`, call the
  error callback that was provided to `shutdown-on-error`, if there is one.
  An error will be logged if the function throws an exception."
  [shutdown-reason]
  {:pre [(map? shutdown-reason)]}
  (when-let [on-error-fn (:on-error-fn shutdown-reason)]
    (try
      (on-error-fn)
      (catch Exception e
        (log/error e "Error occurred during shutdown")))))
