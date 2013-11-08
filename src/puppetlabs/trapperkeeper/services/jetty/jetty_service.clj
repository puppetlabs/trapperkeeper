(ns puppetlabs.trapperkeeper.services.jetty.jetty-service
  (:require
    [puppetlabs.trapperkeeper.services.jetty.jetty-core :as core]
    [puppetlabs.trapperkeeper.core :refer [defservice]])
  (:use [plumbing.core :only [fnk]]))

(defservice jetty-service
  "Provides a Jetty web server as a service"
  {:depends [[:config-service get-in-config]]
   :provides [add-ring-handler join shutdown]}
  (let [config    (or (get-in-config [:jetty]) {})
        webserver (core/start-webserver config)]
    {:add-ring-handler  (partial core/add-ring-handler webserver)
     :join              (partial core/join webserver)
     :shutdown          (partial core/shutdown webserver)}))