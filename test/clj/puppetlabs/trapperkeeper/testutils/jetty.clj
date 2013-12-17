(ns puppetlabs.trapperkeeper.testutils.jetty
  (:require [puppetlabs.trapperkeeper.services.jetty.jetty-core :as jetty]))

(defmacro with-test-jetty
  "Constructs and starts an embedded Jetty on a random port, and
  evaluates `body` inside a try/finally block that takes care of
  tearing down the webserver.

  `app` - The ring application the webserver should serve

  `port-var` - Inside of `body`, the variable named `port-var`
  contains the port number the webserver is listening on

  Example:

      (let [app (constantly {:status 200 :headers {} :body \"OK\"})]
        (with-test-jetty app port
          ;; Hit the embedded webserver
          (http-client/get (format \"http://localhost:%s\" port))))
  "
  [app port-var & body]
  `(let [srv#      (jetty/start-webserver {:port 0 :join? false})
         _#        (jetty/add-ring-handler srv# ~app "/")
         ~port-var (-> (:server srv#)
                       (.getConnectors)
                       (first)
                       (.getLocalPort))]
     (try
       ~@body
       (finally
         (jetty/shutdown srv#)))))
