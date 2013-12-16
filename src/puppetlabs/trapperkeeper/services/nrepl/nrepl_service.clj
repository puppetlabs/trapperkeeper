(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service
  (:require
    [clojure.tools.logging :as log]
    [clojure.tools.nrepl.server :as nrepl]
    [puppetlabs.kitchensink.core :refer [parse-bool]]
    [puppetlabs.trapperkeeper.core :refer [defservice]]))


;; If no port is specified in the config then 7888 is used
(def ^{:private true} default-nrepl-port 7888)
(def ^{:private true} default-bind-addr "0.0.0.0")

(def ^{:private true} nrepl-server (atom nil))

(defn- startup-nrepl
  [config]
  {:pre [(map? config)]}
  (let [enabled (parse-bool (get-in config [:nrepl :enabled] false))
        port    (get-in config [:nrepl :port] default-nrepl-port)
        bind    (get-in config [:nrepl :bind] default-bind-addr)]
    (if (true? enabled)
      (do (log/debug "Starting nrepl service on port" port)
          (compare-and-set! nrepl-server nil (nrepl/start-server :port port :bind bind)))
      (log/debug "nrepl service disabled, not starting")))

  (println "nrepl server values: " @nrepl-server))

(defn- shutdown-nrepl
  [config]
  {:pre [(map? config)]}
  (if (not (nil? @nrepl-server))
    (do (log/debug "Shutting down nrepl service")
        (nrepl/stop-server @nrepl-server))))

(defservice nrepl-service
  "The nREPL trapperkeeper service starts up a Clojure network REPL (nREPL) server attached to the running
   trapperkeeper process. It is configured in the following manner:

   [nrepl]
   enabled=true
   port=7888

   The nrepl service will only start if enabled is set to true, and the port specified which port nREPL should bind to.
   If no port is specified then the default port of 7888 is used."
  {:depends [[:config-service get-config]]
   :provides [shutdown]}
  (startup-nrepl (get-config))
  {:shutdown (partial shutdown-nrepl (get-config))})



