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
   (-> other-args
       (conj "--config" empty-config )
       (trapperkeeper/parse-cli-args!)
       (trapperkeeper/bootstrap))))

(defn bootstrap*-with-empty-config
  [services other-args]
  (let [cli-data (->
                   (conj other-args "--config" empty-config)
                   (trapperkeeper/parse-cli-args!))]
    (trapperkeeper/bootstrap* services cli-data)))

(defn parse-and-bootstrap
  ([bootstrap-config]
   (parse-and-bootstrap bootstrap-config {:config empty-config}))
  ([bootstrap-config cli-data]
   (-> bootstrap-config
       bootstrap/parse-bootstrap-config!
       (trapperkeeper/bootstrap* cli-data))))