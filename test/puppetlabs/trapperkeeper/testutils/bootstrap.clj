(ns puppetlabs.trapperkeeper.testutils.bootstrap
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]))

(def empty-config "./test-resources/config/empty.ini")

(defn bootstrap-services-with-empty-config
  [services]
  (trapperkeeper/bootstrap* services {:config empty-config}))

(defn bootstrap-with-empty-config
  ([]
   (bootstrap-with-empty-config []))
  ([other-args]
   (trapperkeeper/bootstrap (conj other-args "--config" empty-config ))))

(defn parse-and-bootstrap
  ([bootstrap-config]
   (parse-and-bootstrap bootstrap-config {:config empty-config}))
  ([bootstrap-config cli-data]
   (-> bootstrap-config
       bootstrap/parse-bootstrap-config!
       (trapperkeeper/bootstrap* cli-data))))