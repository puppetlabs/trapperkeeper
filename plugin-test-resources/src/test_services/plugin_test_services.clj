;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; IMPORTANT
;;
;; If you change this file, you need to run the following command to update the
;; .jar generated from it (for testing plugins):
;;
;;      zip -r ../plugins/test-service.jar test_services
;;
;; This requires that your pwd is
;;      plugin-test-resources/src
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns test-services.plugin-test-services
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defservice plugin-test-service
            {:depends []
             :provides [moo]}
            {:moo (fn [] "This message comes from the plugin test service.")})

