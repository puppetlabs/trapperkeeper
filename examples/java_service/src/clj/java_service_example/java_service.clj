(ns java-service-example.java-service
  (:import (java_service_example ServiceImpl))
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]
            [clojure.tools.logging :as log]))

(defservice java-service
  {:depends  []
   :provides [msg-fn meaning-of-life-fn]}

  ;; Service functions are implemented in a java `ServiceImpl` class
  {:msg-fn (fn [](ServiceImpl/getMessage))
   :meaning-of-life-fn (fn [] (ServiceImpl/getMeaningOfLife))})

(defservice java-service-consumer
  {:depends [[:java-service msg-fn meaning-of-life-fn]
             [:shutdown-service request-shutdown]]
   :provides []}
  (log/info "Java service consumer!")
  (log/infof "The message from Java is: '%s'" (msg-fn))
  (log/infof "The meaning of life is: '%s'" (meaning-of-life-fn))
  (request-shutdown))
