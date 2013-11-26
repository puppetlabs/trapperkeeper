(ns puppetlabs.trapperkeeper.services.jetty.jetty-config-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [puppetlabs.trapperkeeper.services.jetty.jetty-config :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]))

(deftest jetty7-minimum-threads-test
  (testing "should return the same number when higher than num-cpus"
    (is (= 500 (jetty7-minimum-threads 500 1))))
  (testing "should set the number to min threads when it is higher and return a warning"
    (with-log-output logs
      (is (= 4 (jetty7-minimum-threads 1 4)))
      (is (= 1 (count (logs-matching #"max-threads = 1 is less than the minium allowed on this system for Jetty 7 to operate." @logs)))))))

(deftest http-configuration
  (testing "should enable need-client-auth"
    (let [config (configure-web-server {:client-auth false})]
      (is (= (get config :client-auth) :need))))

  (let [old-config {:keystore       "/some/path"
                    :key-password   "pw"
                    :truststore     "/some/other/path"
                    :trust-password "otherpw"}]
    (testing "should not muck with keystore/truststore settings if PEM-based SSL settings are not provided"
      (let [processed-config (configure-web-server old-config)]
        (is (= old-config
               (select-keys processed-config [:keystore :key-password :truststore :trust-password])))))

    (testing "should fail if some but not all of the PEM-based SSL settings are found"
      (let [partial-pem-config (merge old-config {:ssl-ca-cert "/some/path"})]
        (is (thrown-with-msg? java.lang.IllegalArgumentException
              #"If configuring SSL from PEM files, you must provide all of the following options"
              (configure-web-server partial-pem-config)))))

    (let [pem-config (merge old-config
                            {:ssl-key     (resource "config/jetty/ssl/private_keys/localhost.pem")
                             :ssl-cert    (resource "config/jetty/ssl/certs/localhost.pem")
                             :ssl-ca-cert (resource "config/jetty/ssl/certs/ca.pem")})]
      (testing "should warn if both keystore-based and PEM-based SSL settings are found"
        (with-log-output logs
          (configure-web-server pem-config)
          (is (= 1 (count (logs-matching #"Found settings for both keystore-based and PEM-based SSL" @logs))))))

      (testing "should prefer PEM-based SSL settings, override old keystore settings
                  with instances of java.security.KeyStore, and remove PEM settings
                  from final jetty config hash"
        (let [processed-config (configure-web-server pem-config)]
          (is (instance? java.security.KeyStore (:keystore processed-config)))
          (is (instance? java.security.KeyStore (:truststore processed-config)))
          (is (string? (:key-password processed-config)))
          (is (not (contains? processed-config :trust-password)))
          (is (not (contains? processed-config :ssl-key)))
          (is (not (contains? processed-config :ssl-cert)))
          (is (not (contains? processed-config :ssl-ca-cert)))))))

  (testing "should set max-threads"
    (let [config (configure-web-server {})]
      (is (contains? config :max-threads))))

  (testing "should merge configuration with initial-configs correctly"
    (let [user-config {:truststore "foo"}
          config      (configure-web-server user-config)]
      (is (= config {:truststore "foo" :max-threads 50 :client-auth :need})))
    (let [user-config {:max-threads 500 :truststore "foo"}
          config      (configure-web-server user-config)]
      (is (= config {:truststore "foo" :max-threads 500 :client-auth :need})))))

