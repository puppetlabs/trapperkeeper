;; NOTE: this code is an adaptation of ring-jetty-handler.
;;  It uses Jetty 9, adds some SSL utility functions, and
;;  provides the ability to dynamically register ring handlers.

(ns trapperkeeper.jetty9.jetty9-core
  "Adapter for the Jetty webserver."
  (:import (org.eclipse.jetty.server Handler Server Request HttpConnectionFactory HttpConfiguration)
           (org.eclipse.jetty.server.handler AbstractHandler ContextHandler
                                                             HandlerCollection ContextHandlerCollection)
           (org.eclipse.jetty.server ServerConnector ConnectionFactory)
           (org.eclipse.jetty.util.thread QueuedThreadPool)
           (org.eclipse.jetty.util.ssl SslContextFactory)
           (javax.servlet.http HttpServletRequest HttpServletResponse)
           [java.util.concurrent Executors])
  (:require [ring.util.servlet :as servlet]
            [clojure.tools.logging :as log]
            [trapperkeeper.jetty9.jetty9-config :as jetty-config])
  (:use     [clojure.string :only (split trim)]
            [com.puppetlabs.utils :only (compare-jvm-versions)]
            [clojure.pprint :only (pprint)]))

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
      (if (string? (options :truststore))
        (.setTrustStorePath context (options :truststore))
        (.setTrustStore context (options :truststore))))
    (when (options :trust-password)
      (.setTrustStorePassword context (options :trust-password)))
    (case (options :client-auth)
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- connection-factory
  []
  (let [http-config (doto (HttpConfiguration.)
                      (.setSendDateHeader true))]
    (into-array ConnectionFactory
      [(HttpConnectionFactory. http-config)])))

(defn- ssl-connector
  "Creates a SslSelectChannelConnector instance."
  [server ssl-ctxt-factory options]
  (doto (ServerConnector. server ssl-ctxt-factory (connection-factory))
    (.setPort (options :ssl-port 443))
    (.setHost (options :host))))

(defn- plaintext-connector
  [server options]
  (doto (ServerConnector. server (connection-factory))
    (.setPort (options :port 80))
    (.setHost (options :host "localhost"))))

(defn- create-server
  "Construct a Jetty Server instance."
  [options]
  (let [server (Server. (QueuedThreadPool. (options :max-threads 50)))]
    (when (options :port)
      (let [connector (plaintext-connector server options)]
        (.addConnector server connector)))
    (when (or (options :ssl?) (options :ssl-port))
      (let [ssl-host          (options :ssl-host (options :host "localhost"))
            options           (assoc options :host ssl-host)
            ssl-ctxt-factory  (ssl-context-factory options)
            connector         (ssl-connector server ssl-ctxt-factory options)
            ciphers           (if-let [txt (options :cipher-suites)]
                                (map trim (split txt #","))
                                (acceptable-ciphers))
            protocols         (if-let [txt (options :ssl-protocols)]
                                (map trim (split txt #",")))]
        (when ciphers
          (.setIncludeCipherSuites ssl-ctxt-factory (into-array ciphers))
          (when protocols
            (.setIncludeProtocols ssl-ctxt-factory (into-array protocols))))
        (.addConnector server connector)))
    server))


;; Functions for trapperkeeper 'webserver' interface

(defn start-webserver
  ;  "Start a Jetty webserver according to the supplied options:
  ;
  ;  :configurator - a function called with the Jetty Server instance
  ;  :port         - the port to listen on (defaults to 80)
  ;  :host         - the hostname to listen on
  ;  :join?        - blocks the thread until server ends (defaults to true)
  ;  :ssl?         - allow connections over HTTPS
  ;  :ssl-port     - the SSL port to listen on (defaults to 443, implies :ssl?)
  ;  :keystore     - the keystore to use for SSL connections
  ;  :key-password - the password to the keystore
  ;  :truststore   - a truststore to use for SSL connections
  ;  :trust-password - the password to the truststore
  ;  :max-threads  - the maximum number of threads to use (default 50)
  ;  :client-auth  - SSL client certificate authenticate, may be set to :need,
  ;                  :want or :none (defaults to :none)"
  [options]
  {:pre [(map? options)]}
  (let [options                       (jetty-config/configure-web-server options)
        ^Server s                     (create-server (dissoc options :configurator))
        ^ContextHandlerCollection chc (ContextHandlerCollection.)
        ^HandlerCollection hc         (HandlerCollection.)]
    (.setHandlers hc (into-array Handler [chc]))
    (.setHandler s hc)
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
    ;(.start ctxt-handler)
    ctxt-handler))

(defn join
  [webserver]
  (.join (:server webserver)))

(defn shutdown
  [webserver]
  (.stop (:server webserver)))

;; TODO: this function is just here to simplify the incremental
;; swap-out of the existing puppetdb jetty code.  This should
;; be removed once we've ported over to trapperkeeper
(defn run-jetty
  [app options]
  (let [webserver (start-webserver options)
        join?     (options :join?)]
    (add-ring-handler webserver app "")
    (when join?
      (join webserver))
    webserver))