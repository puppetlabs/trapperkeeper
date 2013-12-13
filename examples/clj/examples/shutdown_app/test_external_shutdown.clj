(ns examples.shutdown-app.test-external-shutdown
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]))

(trapperkeeper/defservice test-service
  {:depends  []
   :provides [shutdown]}
  {:shutdown #(println "If you see this printed out then shutdown works correctly!")})

(defn -main
  [& args]
  (println "Waiting for a shutdown signal - use Ctrl-C or kill.")
  (println "You should see a message printed out when services are being shutdown.")
  (trapperkeeper/run
    {:config "test-resources/config/empty.ini"
     :bootstrap-config "examples/clj/examples/shutdown_app/bootstrap.cfg"}))
