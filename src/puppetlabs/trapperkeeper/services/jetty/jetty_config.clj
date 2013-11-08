(ns trapperkeeper.jetty9.jetty9-config
  (:import [java.security KeyStore])
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.ssl :as ssl]
            [com.puppetlabs.utils :as pl-utils])
  (:use [com.puppetlabs.utils :only (missing?)]))

(defn configure-web-server-ssl-from-pems
  "Configures the web server's SSL settings based on Puppet PEM files, rather than
  via a java keystore (jks) file.  The configuration map returned by this function
  will have overwritten any existing keystore-related settings to use in-memory
  KeyStore objects, which are constructed based on the values of
  `:ssl-key`, `:ssl-cert`, and `:ssl-ca-cert` from
  the input map.  The output map does not include the `:puppet-*` keys, as they
  are not meaningful to the web server implementation."
  [{:keys [ssl-key ssl-cert ssl-ca-cert] :as options}]
  {:pre  [ssl-key
          ssl-cert
          ssl-ca-cert]
   :post [(map? %)
          (instance? KeyStore (:keystore %))
          (string? (:key-password %))
          (instance? KeyStore (:truststore %))
          (missing? % :trust-password :ssl-key :ssl-cert :ssl-ca-cert)]}
  (let [old-ssl-config-keys [:keystore :truststore :key-password :trust-password]
        old-ssl-config      (select-keys options old-ssl-config-keys)]
    (when (pos? (count old-ssl-config))
      (log/warn (format "Found settings for both keystore-based and Puppet PEM-based SSL; using PEM-based settings, ignoring %s"
                  (keys old-ssl-config)))))
  (let [truststore  (-> (ssl/keystore)
                      (ssl/assoc-cert-file! "PuppetDB CA" ssl-ca-cert))
        keystore-pw (pl-utils/uuid)
        keystore    (-> (ssl/keystore)
                      (ssl/assoc-private-key-file! "PuppetDB Agent Private Key" ssl-key keystore-pw ssl-cert))]
    (-> options
      (dissoc :ssl-key :ssl-ca-cert :ssl-cert :trust-password)
      (assoc :keystore keystore)
      (assoc :key-password keystore-pw)
      (assoc :truststore truststore))))

;; TODO: can we get rid of this?
(defn jetty7-minimum-threads
  "Given a thread count, make sure it meets the minimum count for Jetty 7 to
  operate. It will return a warning if it does not, and return the minimum
  instead of the original value.

  This is to work-around a bug/feature in Jetty 7 that blocks the web server
  when max-threads is less than the number of cpus on a system.

  See: http://projects.puppetlabs.com/issues/22168 for more details.

  This bug is solved in Jetty 9, so this check can probably be removed if we
  upgrade."
  ([threads]
    (jetty7-minimum-threads threads (inc (pl-utils/num-cpus))))

  ([threads min-threads]
    {:pre [(pos? threads)
           (pos? min-threads)]
     :post [(pos? %)]}
    (if (< threads min-threads)
      (do
        (log/warn (format "max-threads = %s is less than the minium allowed on this system for Jetty 7 to operate. This will be automatically increased to the safe minimum: %s"
                    threads min-threads))
        min-threads)
      threads)))

(defn configure-web-server
  "Update the supplied config map with information about the HTTP webserver to
  start. This will specify client auth, and add a default host/port
  http://puppetdb:8080 if none are supplied (and SSL is not specified)."
  [options]
  {:pre  [(map? options)]
   :post [(map? %)
          (missing? % :ssl-key :ssl-cert :ssl-ca-cert)]}
  (let [initial-config    {:max-threads 50}
        merged-options    (merge initial-config options)
        pem-required-keys [:ssl-key :ssl-cert :ssl-ca-cert]
        pem-config        (select-keys options pem-required-keys)]
    (-> (condp = (count pem-config)
          3 (configure-web-server-ssl-from-pems options)
          0 options
          (throw (IllegalArgumentException.
                   (format "Found SSL config options: %s; If configuring SSL from Puppet PEM files, you must provide all of the following options: %s"
                     (keys pem-config) pem-required-keys))))
      (assoc :client-auth :need)
      (assoc :max-threads (jetty7-minimum-threads (:max-threads merged-options))))))