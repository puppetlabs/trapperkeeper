(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service
  (:require
    [clojure.tools.logging :as log]
    [nrepl.server :as nrepl]
    [cider.nrepl :as cider]
    [puppetlabs.kitchensink.core :refer [to-bool]]
    [puppetlabs.trapperkeeper.core :refer [defservice]]
    [puppetlabs.i18n.core :as i18n]))


;; If no port is specified in the config then 7888 is used
(def ^{:private true} default-nrepl-port  7888)
(def ^{:private true} default-bind-addr   "0.0.0.0")
(def ^{:private true} default-middlewares [])

(defn- parse-middlewares-if-necessary
  [middlewares]
  (if (string? middlewares)
    (read-string middlewares)
    (map symbol middlewares)))

(defn- process-middlewares [middlewares]
  (let [middlewares (parse-middlewares-if-necessary middlewares)]
    (doseq [middleware (map #(symbol (namespace %)) middlewares)]
      (require middleware))
    (map #(resolve %) middlewares)))

(defn process-config
  [get-in-config]
  {:enabled? (to-bool (get-in-config [:nrepl :enabled]))
   :port     (get-in-config [:nrepl :port] default-nrepl-port)
   :bind     (get-in-config [:nrepl :host] default-bind-addr)
   :middlewares  (process-middlewares (get-in-config [:nrepl :middlewares] default-middlewares))
   :cider-middlewares (when (get-in-config [:nrepl :cider-enabled]) (map resolve cider/cider-middleware))})

(defn- startup-nrepl
  [get-in-config]
  (let [{:keys [enabled? port bind middlewares cider-middlewares]} (process-config get-in-config)]
    (if enabled?
      (let [handler (apply nrepl/default-handler (concat middlewares cider-middlewares))]
        (log/info (i18n/trs "Starting nREPL service on {0} port {1}" bind port))
        (nrepl/start-server :port port :bind bind :handler handler))
      (log/info (i18n/trs "nREPL service disabled, not starting")))))

(defn- shutdown-nrepl
  [nrepl-server]
  (when nrepl-server
    (log/info (i18n/trs "Shutting down nREPL service"))
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



