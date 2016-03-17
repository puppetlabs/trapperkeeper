(ns examples.shutdown-app.test-external-shutdown
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as testutils]))

(trapperkeeper/defservice test-service
  []
  (stop [this context]
    (println "If you see this printed out then shutdown works correctly!")
    context))

(defn -main
  [& args]
  (println "Waiting for a shutdown signal - use Ctrl-C or kill.")
  (println "You should see a message printed out when services are being shutdown.")
  (trapperkeeper/run
   {:config testutils/empty-config
    :bootstrap-config "examples/shutdown_app/bootstrap.cfg"}))
