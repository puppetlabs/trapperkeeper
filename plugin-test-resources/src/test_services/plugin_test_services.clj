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
;; This requires that your cwd is
;;      plugin-test-resources/src
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(ns test-services.plugin-test-services
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defprotocol PluginTestService
  (moo [this]))

(defservice plugin-test-service
            PluginTestService
            []
            (moo [this] "This message comes from the plugin test service."))

