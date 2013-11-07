(ns puppetlabs.trapperkeeper.examples.bootstrapping.test-services
  (:require [plumbing.core :refer [fnk]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]))

(defn invalid-service-graph-service
  []
  {:test-service "hi"})

(defservice hello-world-service
  {:depends  []
   :provides [hello-world]}
  {:hello-world (fn [] "hello world")})

(defservice foo-test-service
  {:depends  []
   :provides [test-fn]}
  {:test-fn (fn [] :foo)})

(defservice classpath-test-service
  {:depends  []
   :provides [test-fn]}
  {:test-fn (fn [] :classpath)})

(defservice cwd-test-service
  {:depends  []
   :provides [test-fn]}
  {:test-fn (fn [] :cwd)})

(defservice cli-test-service
  {:depends  []
   :provides [test-fn]}
  {:test-fn (fn [] :cli)})
