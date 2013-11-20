;; NOTE: this code is an adaptation of ring-jetty-handler.
;;  It adds some SSL utility functions, and
;;  provides the ability to dynamically register ring handlers.

(ns puppetlabs.trapperkeeper.services.jetty.jetty-core
  "Adapter for the Jetty webserver."
  (:import (org.eclipse.jetty.server Handler Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler ContextHandler HandlerCollection ContextHandlerCollection GzipHandler)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (org.eclipse.jetty.server.ssl SslSelectChannelConnector)
           (org.eclipse.jetty.server.nio SelectChannelConnector)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           (java.util.concurrent Executors))
  (:require [ring.util.servlet :as servlet]
            [clojure.string :refer [split trim]]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.services.jetty.jetty-config :as jetty-config]
            [puppetlabs.kitchensink.core :refer [compare-jvm-versions]]))

;; Work around an issue with OpenJDK's PKCS11 implementation preventing TLSv1
;; connections from working correctly
;;
;; http://stackoverflow.com/questions/9586162/openjdk-and-php-ssl-connection-fails
;; https://bugs.launchpad.net/ubuntu/+source/openjdk-6/+bug/948875
(if (re-find #"OpenJDK" (System/getProperty "java.vm.name"))
  (try
    (let [klass     (Class/forName "sun.security.pkcs11.SunPKCS11")
          blacklist (filter #(instance? klass %) (java.security.Security/getProviders))]
      (doseq [provider blacklist]
        (log/info (str "Removing buggy security provider " provider))
        (java.security.Security/removeProvider (.getName provider))))
    (catch ClassNotFoundException e)
    (catch Throwable e
      (log/error e "Could not remove security providers; HTTPS may not work!"))))

;; Due to weird issues between JSSE and OpenSSL clients on some 1.7
;; jdks when using Diffie-Hellman exchange, we need to only enable
;; RSA-based ciphers.
;;
;; https://forums.oracle.com/forums/thread.jspa?messageID=10999587
;; https://issues.apache.org/jira/browse/APLO-287
;;
;; If not running on an affected JVM version, this is nil.
(defn acceptable-ciphers
  ([]
    (acceptable-ciphers (System/getProperty "java.version")))
  ([jvm-version]
    (let [known-good-version "1.7.0_05"]
      (if (pos? (compare-jvm-versions jvm-version known-good-version))
        ;; We're more recent than the last known-good version, and hence
        ;; are busted
        ["TLS_RSA_WITH_AES_256_CBC_SHA256"
         "TLS_RSA_WITH_AES_256_CBC_SHA"
         "TLS_RSA_WITH_AES_128_CBC_SHA256"
         "TLS_RSA_WITH_AES_128_CBC_SHA"
         "SSL_RSA_WITH_RC4_128_SHA"
         "SSL_RSA_WITH_3DES_EDE_CBC_SHA"
         "SSL_RSA_WITH_RC4_128_MD5"]))))

(defn- proxy-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map  (servlet/build-request-map request)
            response-map (handler request-map)]
        (when response-map
          (servlet/update-servlet-response response response-map)
          (.setHandled base-request true))))))

(defn- ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [options]
  (let [context (SslContextFactory.)]
    (if (string? (options :keystore))
      (.setKeyStorePath context (options :keystore))
      (.setKeyStore context (options :keystore)))
    (.setKeyStorePassword context (options :key-password))
    (when (options :truststore)
      (.setTrustStore context (options :truststore)))
    (when (options :trust-password)
      (.setTrustStorePassword context (options :trust-password)))
    (case (options :client-auth)
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- ssl-connector
  "Creates a SslSelectChannelConnector instance."
  [options]
  (doto (SslSelectChannelConnector. (ssl-context-factory options))
    (.setPort (options :ssl-port 443))
    (.setHost (options :host))))

(defn plaintext-connector
  [options]
  (doto (SelectChannelConnector.)
    (.setPort (options :port 80))
    (.setHost (options :host "localhost"))))

(defn- create-server
  "Construct a Jetty Server instance."
  [options]
  (let [server (doto (Server.)
                 (.setSendDateHeader true))]
    (when (options :port)
      (.addConnector server (plaintext-connector options)))

    (when (or (options :ssl?) (options :ssl-port))
      (let [ssl-host  (options :ssl-host (options :host "localhost"))
            options   (assoc options :host ssl-host)
            connector (ssl-connector options)
            ciphers   (if-let [txt (options :cipher-suites)]
                        (map trim (split txt #","))
                        (acceptable-ciphers))
            protocols (if-let [txt (options :ssl-protocols)]
                        (map trim (split txt #",")))]
        (when ciphers
          (let [fac (.getSslContextFactory connector)]
            (.setIncludeCipherSuites fac (into-array ciphers))
            (when protocols
              (.setIncludeProtocols fac (into-array protocols)))))
        (.addConnector server connector)))
    server))

;; Functions for trapperkeeper 'webserver' interface

(defn start-webserver
    "Start a Jetty webserver according to the supplied options:

    :configurator - a function called with the Jetty Server instance
    :port         - the port to listen on (defaults to 80)
    :host         - the hostname to listen on
    :join?        - blocks the thread until server ends (defaults to true)
    :ssl?         - allow connections over HTTPS
    :ssl-port     - the SSL port to listen on (defaults to 443, implies :ssl?)
    :keystore     - the keystore to use for SSL connections
    :key-password - the password to the keystore
    :truststore   - a truststore to use for SSL connections
    :trust-password - the password to the truststore
    :max-threads  - the maximum number of threads to use (default 50)
    :client-auth  - SSL client certificate authenticate, may be set to :need,
                    :want or :none (defaults to :none)"
  [options]
  {:pre [(map? options)]}
  (let [options                       (jetty-config/configure-web-server options)
        ^Server s                     (create-server (dissoc options :configurator))
        ^ContextHandlerCollection chc (ContextHandlerCollection.)
        ^HandlerCollection hc         (HandlerCollection.)]
    (.setHandlers hc (into-array Handler [chc]))
    (->> (doto (GzipHandler.)
           (.setHandler hc))
         (.setHandler s))
    (when-let [configurator (:configurator options)]
      (configurator s))
    (.start s)
    {:server   s
     :handlers chc}))

(defn add-ring-handler
  [webserver handler path]
  (let [handler-coll (:handlers webserver)
        ctxt-handler (doto (ContextHandler. path)
                       (.setHandler (proxy-handler handler)))]
    (.addHandler handler-coll ctxt-handler)
    (.start ctxt-handler)
    ctxt-handler))

(defn join
  [webserver]
  (.join (:server webserver)))

(defn shutdown
  [webserver]
  (.stop (:server webserver)))
