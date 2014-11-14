(ns puppetlabs.trapperkeeper.testutils.services)

;; This protocol is used by pl.tk.services-test.
;; However, it cannot be defined in that namespace, because it contains
;; '(:require [puppetlabs.trapperkeeper.services :refer :all]'
;; which pulls in the Lifecycle protocol and defines a var named 'start' -
;; which conflicts with the 'start' function in this protocol.
(defprotocol BadServiceProtocol
  (start [this]))