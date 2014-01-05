(ns puppetlabs.trapperkeeper.testutils.bootstrap
  (:require [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.internal :refer [parse-cli-args!]]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.config :as config]))

(def empty-config "./test-resources/config/empty.ini")

(defn bootstrap-services-with-cli-data
  [services cli-data]
  (bootstrap/bootstrap-services services (config/parse-config-data cli-data)))

(defn bootstrap-services-with-empty-config
  ([services]
    (bootstrap-services-with-cli-data services {:config empty-config}))
  ([services other-args]
    (->> (conj other-args "--config" empty-config)
         (parse-cli-args!)
         (bootstrap-services-with-cli-data services))))

(defn bootstrap-with-empty-config
  ([]
   (bootstrap-with-empty-config []))
  ([other-args]
   (-> other-args
       (conj "--config" empty-config )
       (parse-cli-args!)
       (bootstrap/bootstrap))))

(defn parse-and-bootstrap
  ([bootstrap-config]
   (parse-and-bootstrap bootstrap-config {:config empty-config}))
  ([bootstrap-config cli-data]
   (-> bootstrap-config
       (bootstrap/parse-bootstrap-config!)
       (bootstrap-services-with-cli-data cli-data))))
