(ns examples.servlet.hello-servlet
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]])
  (:import  [examples.servlet HelloServlet]))

(defservice hello-servlet-service
  {:depends  [[:webserver-service add-servlet-handler]]
   :provides []}
  (add-servlet-handler (HelloServlet. "Hello servlet world!") "/hello")
  (add-servlet-handler (HelloServlet. "Well hello") "/hi")
  {})
