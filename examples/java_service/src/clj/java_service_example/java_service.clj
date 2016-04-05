(ns java-service-example.java-service
  (:import (java_service_example ServiceImpl))
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [clojure.tools.logging :as log]))

(defprotocol JavaService
  (msg-fn [this])
  (meaning-of-life-fn [this]))

(defservice java-service
  JavaService
  []
  ;; Service functions are implemented in a java `ServiceImpl` class
  (msg-fn [this] (ServiceImpl/getMessage))
  (meaning-of-life-fn [this] (ServiceImpl/getMeaningOfLife)))

(defservice java-service-consumer
  [[:JavaService msg-fn meaning-of-life-fn]
   [:ShutdownService request-shutdown]]
  (init [this context]
    (log/info "Java service consumer!")
    (log/infof "The message from Java is: '%s'" (msg-fn))
    (log/infof "The meaning of life is: '%s'" (meaning-of-life-fn))
    context)
  (start [this context]
    (request-shutdown)
    context))
