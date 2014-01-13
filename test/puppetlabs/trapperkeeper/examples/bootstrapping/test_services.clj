(ns puppetlabs.trapperkeeper.examples.bootstrapping.test-services
  (:require [plumbing.core :refer [fnk]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defn invalid-service-graph-service
  []
  {:test-service "hi"})

(defprotocol HelloWorldService
  (hello-world [this]))

(defprotocol TestService
  (test-fn [this]))

(defservice hello-world-service
            HelloWorldService
            []
            (hello-world [this] "hello world"))

(defservice foo-test-service
            TestService
            []
            (test-fn [this] :foo))

(defservice classpath-test-service
            TestService
            []
            (test-fn [this] :classpath))

(defservice cwd-test-service
            TestService
            []
            (test-fn [this] :cwd))

(defservice cli-test-service
            TestService
            []
            (test-fn [this] :cli))
