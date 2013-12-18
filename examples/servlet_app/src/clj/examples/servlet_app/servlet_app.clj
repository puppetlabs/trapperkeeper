(ns examples.servlet-app.servlet-app
  (:import  [examples.servlet_app MyServlet])
  (:require [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defservice hello-servlet-service
  {:depends  [[:webserver-service add-servlet-handler]]
   :provides []}
  (add-servlet-handler (MyServlet. "Hi there!") "/hello")
  (add-servlet-handler (MyServlet. "See you later!") "/goodbye")
  {})
