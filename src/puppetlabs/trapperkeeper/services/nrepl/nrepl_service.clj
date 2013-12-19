(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service
  (:require
    [clojure.tools.logging :as log]
    [clojure.tools.nrepl.server :as nrepl]
    [puppetlabs.kitchensink.core :refer [parse-bool]]
    [puppetlabs.trapperkeeper.core :refer [defservice]]))


;; If no port is specified in the config then 7888 is used
(def ^{:private true} default-nrepl-port 7888)
(def ^{:private true} default-bind-addr  "0.0.0.0")

(defn- startup-nrepl
  [get-in-config]
  (let [enabled?     (parse-bool (get-in-config [:nrepl :enabled]))
        port         (get-in-config [:nrepl :port] default-nrepl-port)
        bind         (get-in-config [:nrepl :host] default-bind-addr)]
    (if enabled?
      (do (log/info "Starting nREPL service on" bind "port" port)
          (nrepl/start-server :port port :bind bind))
      (log/info "nREPL service disabled, not starting"))))

(defn- shutdown-nrepl
  [nrepl-server]
  (when nrepl-server
    (log/info "Shutting down nREPL service")
    (nrepl/stop-server nrepl-server)))

(defservice nrepl-service
  "The nREPL trapperkeeper service starts up a Clojure network REPL (nREPL) server attached to the running
   trapperkeeper process. It is configured in the following manner:

   [nrepl]
   enabled=true
   port=7888
   host=0.0.0.0

   The nrepl service will only start if enabled is set to true, and the port specified which port nREPL should bind to.
   If no port is specified then the default port of 7888 is used."
  {:depends [[:config-service get-in-config]]
   :provides [shutdown]}
  (let [nrepl-server (startup-nrepl get-in-config)]
    {:shutdown (partial shutdown-nrepl nrepl-server)}))



