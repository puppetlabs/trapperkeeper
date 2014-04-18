(ns puppetlabs.trapperkeeper.testutils.bootstrap
  (:require [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.kitchensink.testutils :as ks-testutils]
            [puppetlabs.trapperkeeper.internal :refer [parse-cli-args!]]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.config :as config]))

(def empty-config "./target/empty.ini")
(fs/touch empty-config)

(defmacro with-app-with-config
  [app services config & body]
  `(ks-testutils/with-no-jvm-shutdown-hooks
     (let [~app (tk/boot-services-with-config ~services ~config)]
       (try
         ~@body
         (finally
           (tk-app/stop ~app))))))

(defn bootstrap-services-with-cli-data
  [services cli-data]
  (tk/boot-services-with-config services (config/parse-config-data cli-data)))

(defmacro with-app-with-cli-data
  [app services cli-data & body]
  `(ks-testutils/with-no-jvm-shutdown-hooks
     (let [~app (bootstrap-services-with-cli-data ~services ~cli-data)]
       (try
         ~@body
         (finally
           (tk-app/stop ~app))))))

(defn bootstrap-services-with-cli-args
  [services cli-args]
  (bootstrap-services-with-cli-data services (parse-cli-args! cli-args)))

(defmacro with-app-with-cli-args
  [app services cli-args & body]
  `(ks-testutils/with-no-jvm-shutdown-hooks
     (let [~app (bootstrap-services-with-cli-args ~services ~cli-args)]
       (try
         ~@body
         (finally
           (tk-app/stop ~app))))))

(defn bootstrap-services-with-empty-config
  [services]
  (bootstrap-services-with-cli-data services {:config empty-config}))

(defmacro with-app-with-empty-config
  [app services & body]
  `(ks-testutils/with-no-jvm-shutdown-hooks
     (let [~app (bootstrap-services-with-empty-config ~services)]
       (try
         ~@body
         (finally
           (tk-app/stop ~app))))))

(defn bootstrap-with-empty-config
  ([]
   (bootstrap-with-empty-config []))
  ([other-args]
   (-> other-args
       (conj "--config" empty-config )
       (parse-cli-args!)
       (tk/boot-with-cli-data))))

(defn parse-and-bootstrap
  ([bootstrap-config]
   (parse-and-bootstrap bootstrap-config {:config empty-config}))
  ([bootstrap-config cli-data]
   (-> bootstrap-config
       (bootstrap/parse-bootstrap-config!)
       (bootstrap-services-with-cli-data cli-data))))
