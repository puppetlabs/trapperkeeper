(ns puppetlabs.trapperkeeper.testutils.bootstrap
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]))

(def empty-config "./test-resources/config/empty.ini")

(defn bootstrap-services-with-empty-config
  ([services]
    (bootstrap/bootstrap-services services {:config empty-config}))
  ([services other-args]
    (->> (conj other-args "--config" empty-config)
         (trapperkeeper/parse-cli-args!)
         (bootstrap/bootstrap-services services))))

(defn bootstrap-with-empty-config
  ([]
   (bootstrap-with-empty-config []))
  ([other-args]
   (-> other-args
       (conj "--config" empty-config )
       (trapperkeeper/parse-cli-args!)
       (bootstrap/bootstrap))))

(defn parse-and-bootstrap
  ([bootstrap-config]
   (parse-and-bootstrap bootstrap-config {:config empty-config}))
  ([bootstrap-config cli-data]
   (-> bootstrap-config
       (bootstrap/parse-bootstrap-config!)
       (bootstrap/bootstrap-services cli-data))))
