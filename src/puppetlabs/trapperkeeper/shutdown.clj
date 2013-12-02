(ns puppetlabs.trapperkeeper.shutdown
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :refer [add-shutdown-hook!]]
            [puppetlabs.trapperkeeper.app :refer [walk-leaves-and-path]]
            [puppetlabs.trapperkeeper.services :refer [service get-service-fn]]
            [plumbing.fnk.pfnk :refer [input-schema output-schema fn->fnk]])
  (:import (puppetlabs.trapperkeeper.app TrapperKeeperApp)))

(def shutdown-fns (atom ()))

(defn shutdown!
  "Perform shutdown on the application by calling all service shutdown hooks.
  Services will be shut down in dependency order."
  []
  (log/info "Beginning shutdown sequence")
  (doseq [f @shutdown-fns]
    (try
      (f)
      (catch Exception e
        (log/error e "Encountered error during shutdown sequence")))))

(defn- wrap-with-shutdown-registration
  "Given an accumulating list of shutdown functions and a path to a service
  in the graph, extract the shutdown function from the service and add it to
  the list."
  [shutdown-fns-atom path orig-fnk]
  (let [in  (input-schema orig-fnk)
        out (output-schema orig-fnk)
        f   (fn [injected-vals]
              (let [result (orig-fnk injected-vals)]
                (when-let [shutdown-fn (result :shutdown)]
                  (swap! shutdown-fns-atom conj shutdown-fn))
                result))]
    (fn->fnk f [in out])))

(defn- create-shutdown-on-error-fn
  [shutdown-reason]
  (fn shutdown-fn
    ([f]
     (shutdown-fn f nil))
    ([f on-error-fn]
     (try
       (f)
       (catch Exception e
         (deliver shutdown-reason {:type         :service-error
                                   :error        e
                                   :on-error-fn  on-error-fn}))))))

(defn register-shutdown-hooks!
  "Walk the graph and register all shutdown functions. The functions
  will be called when the JVM shuts down, or by calling `shutdown!`."
  [graph]
  (let [wrapped-graph         (walk-leaves-and-path
                                (partial wrap-with-shutdown-registration shutdown-fns)
                                graph)
        shutdown-reason       (promise)
        shutdown-on-error     (create-shutdown-on-error-fn shutdown-reason)
        shutdown-service      (service :shutdown-service
                                       {:depends  []
                                        :provides [request-shutdown wait-for-shutdown shutdown-on-error]}
                                       {:request-shutdown   #(deliver shutdown-reason {:type :requested})
                                        :wait-for-shutdown  #(deref shutdown-reason)
                                        :shutdown-on-error  shutdown-on-error})]
    (add-shutdown-hook! #(do
                           ;; TODO remove unneccessary 'do'
                           (when-not (realized? shutdown-reason)
                             (shutdown!)
                             (deliver shutdown-reason {:type :jvm-shutdown-hook}))))
    (merge (shutdown-service) wrapped-graph)))

(defn request-shutdown!
  "TODO docs"
  [^TrapperKeeperApp app]
  ((get-service-fn app :shutdown-service :request-shutdown)))

(defn wait-for-shutdown
  "TODO docs"
  [^TrapperKeeperApp app]
  ((get-service-fn app :shutdown-service :wait-for-shutdown)))
