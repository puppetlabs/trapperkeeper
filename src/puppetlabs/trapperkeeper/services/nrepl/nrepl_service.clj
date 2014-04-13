(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service
  (:require
    [clojure.tools.logging :as log]
    [clojure.tools.nrepl.server :as nrepl]
    [puppetlabs.kitchensink.core :refer [parse-bool boolean?]]
    [puppetlabs.trapperkeeper.core :refer [defservice]]))


;; If no port is specified in the config then 7888 is used
(def ^{:private true} default-nrepl-port 7888)
(def ^{:private true} default-bind-addr  "0.0.0.0")

(defn process-config
  [get-in-config]
  {:enabled? (let [enabled? (get-in-config [:nrepl :enabled])]
               (if (boolean? enabled?)
                 enabled?
                 (parse-bool enabled?)))
   :port     (get-in-config [:nrepl :port] default-nrepl-port)
   :bind     (get-in-config [:nrepl :host] default-bind-addr)})

(defn- startup-nrepl
  [get-in-config]
  (let [{:keys [enabled? port bind]} (process-config get-in-config)]
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
  [[:ConfigService get-in-config]]
  (init [this context]
        (let [nrepl-server (startup-nrepl get-in-config)]
          (assoc context :nrepl-server nrepl-server)))
  (stop [this context]
        (shutdown-nrepl (context :nrepl-server))
        context))



