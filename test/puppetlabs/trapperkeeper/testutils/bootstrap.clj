(ns puppetlabs.trapperkeeper.testutils.bootstrap
  (:require [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.kitchensink.testutils :as ks-testutils]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.config :as config]))

(def empty-config "./target/empty.ini")
(fs/touch empty-config)

(defmacro with-app-with-config
  [app services config & body]
  `(ks-testutils/with-no-jvm-shutdown-hooks
     (let [~app (internal/app->shutdown-error-throwable-or-app
                  (tk/boot-services-with-config ~services ~config))]
       (try
         ~@body
         (finally
           (tk-app/stop ~app))))))

(defn bootstrap-services-with-cli-data
  ([services cli-data]
    (bootstrap-services-with-cli-data services cli-data false))
  ([services cli-data return-app-on-service-error?]
    (let [app (tk/boot-services-with-config services
                                            (config/parse-config-data
                                              cli-data))]
      (if return-app-on-service-error?
        app
        (internal/app->shutdown-error-throwable-or-app app)))))

(defmacro with-app-with-cli-data
  [app services cli-data & body]
  `(ks-testutils/with-no-jvm-shutdown-hooks
     (let [~app (bootstrap-services-with-cli-data ~services ~cli-data)]
       (try
         ~@body
         (finally
           (tk-app/stop ~app))))))

(defn bootstrap-services-with-cli-args
  ([services cli-args]
   (bootstrap-services-with-cli-args services cli-args false))
  ([services cli-args return-app-on-service-error?]
   (bootstrap-services-with-cli-data services
                                     (internal/parse-cli-args! cli-args)
                                     return-app-on-service-error?)))

(defmacro with-app-with-cli-args
  [app services cli-args & body]
  `(ks-testutils/with-no-jvm-shutdown-hooks
     (let [~app (bootstrap-services-with-cli-args ~services ~cli-args)]
       (try
         ~@body
         (finally
           (tk-app/stop ~app))))))

(defn bootstrap-services-with-empty-config
  ([services]
   (bootstrap-services-with-empty-config services false))
  ([services return-app-on-service-error?]
   (bootstrap-services-with-cli-data services
                                     {:config empty-config}
                                     return-app-on-service-error?)))

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
   (bootstrap-with-empty-config other-args false))
  ([other-args return-app-on-service-error?]
     (let [app (-> other-args
                     (conj "--config" empty-config )
                     (internal/parse-cli-args!)
                     (tk/boot-with-cli-data))]
       (if return-app-on-service-error?
         app
         (internal/app->shutdown-error-throwable-or-app app)))))

(defn parse-and-bootstrap
  ([bootstrap-config]
   (parse-and-bootstrap bootstrap-config {:config empty-config}))
  ([bootstrap-config cli-data]
   (parse-and-bootstrap bootstrap-config cli-data false))
  ([bootstrap-config cli-data return-app-on-service-error?]
   (-> bootstrap-config
       (bootstrap/parse-bootstrap-config!)
       (bootstrap-services-with-cli-data cli-data
                                         return-app-on-service-error?))))