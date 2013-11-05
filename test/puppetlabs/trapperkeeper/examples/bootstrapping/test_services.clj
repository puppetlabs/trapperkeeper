(ns puppetlabs.trapperkeeper.examples.bootstrapping.test-services
  (:require [plumbing.core :refer [fnk]]))


(defn invalid-service-graph-service
  []
  {:test-service "hi"})

(defn hello-world-service
  []
  {:hello-world-service
    (fnk []
         {:hello-world (fn [] "hello world")})})

(defn foo-test-service
  []
  {:test-service
    (fnk []
         {:test-fn (fn [] :foo)})})

(defn classpath-test-service
  []
  {:test-service
    (fnk []
         {:test-fn (fn [] :classpath)})})

(defn cwd-test-service
  []
  {:test-service
    (fnk []
         {:test-fn (fn [] :cwd)})})

(defn cli-test-service
  []
  {:test-service
    (fnk []
         {:test-fn (fn [] :cli)})})
