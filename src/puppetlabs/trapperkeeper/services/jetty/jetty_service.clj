(ns puppetlabs.trapperkeeper.services.jetty.jetty-service
  (:require
    [puppetlabs.trapperkeeper.services.jetty.jetty-core :as core]
    [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defservice webserver-service
  "Provides a Jetty web server as a service"
  {:depends [[:config-service get-in-config]]
   :provides [add-ring-handler add-servlet-handler join shutdown]}
  (let [config    (or (get-in-config [:webserver])
                      ;; Here for backward compatibility with existing projects
                      (get-in-config [:jetty])
                      {})
        webserver (core/start-webserver config)]
    {:add-ring-handler    (partial core/add-ring-handler webserver)
     :add-servlet-handler (partial core/add-servlet-handler webserver)
     :join                (partial core/join webserver)
     :shutdown            (partial core/shutdown webserver)}))
