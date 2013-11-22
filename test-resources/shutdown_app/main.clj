(ns shutdown-app.main
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]))

(trapperkeeper/defservice test-service
  {:depends  []
   :provides [shutdown]}
  {:shutdown #(println "If you see this printed out then shutdown works correctly!")})

(defn -main
  [& args]
  (trapperkeeper/bootstrap
    {:config "./test-resources/config/empty.ini"
     :bootstrap-config "test-resources/shutdown_app/bootstrap.cfg"})
  (println "Waiting 60 seconds for a shutdown signal - use Ctrl-C or kill.")
  (println "You should see a message printed out when services are being shutdown.")
  (Thread/sleep 60000))
