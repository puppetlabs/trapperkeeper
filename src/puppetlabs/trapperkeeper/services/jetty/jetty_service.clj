(ns trapperkeeper.jetty9.jetty9-service
  (:require [trapperkeeper.jetty9.jetty9-core :as core])
  (:use [plumbing.core :only [fnk]]))

(defn jetty9-service
  []
  {:webserver-service
   (fnk ^{:output-schema
          {:add-ring-handler true
           :join true}}
     [[:config-service config]]
     (let [webserver (core/start-webserver (config :jetty))]
       {:add-ring-handler  (partial core/add-ring-handler webserver)
        :join              (partial core/join webserver)}))})