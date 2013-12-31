(ns puppetlabs.trapperkeeper.shutdown-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.shutdown :refer :all]
            [puppetlabs.trapperkeeper.core :as trapperkeeper]
            [puppetlabs.trapperkeeper.app :refer [get-service-fn]]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-empty-config]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.kitchensink.testutils.fixtures :refer [with-no-jvm-shutdown-hooks]]))

(use-fixtures :once with-no-jvm-shutdown-hooks)

(deftest shutdown
  (testing "service with shutdown hook gets called during shutdown"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  []
                                      :provides [shutdown]}
                                     {:shutdown #(reset! shutdown-called? true)})
          app               (bootstrap-services-with-empty-config [(test-service)])]
      (is (false? @shutdown-called?))
      (shutdown!)
      (is (true? @shutdown-called?))))

  (testing "services are shut down in dependency order"
    (let [order       (atom [])
          service1    (service :service1
                               {:depends  []
                                :provides [shutdown]}
                               {:shutdown #(swap! order conj 1)})
          service2    (service :service2
                               {:depends  [service1]
                                :provides [shutdown]}
                               {:shutdown #(swap! order conj 2)})
          app         (bootstrap-services-with-empty-config [(service1) (service2)])]
      (is (empty? @order))
      (shutdown!)
      (is (= @order [2 1]))))

  (testing "services continue to shut down when one throws an exception"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  []
                                      :provides [shutdown]}
                                     {:shutdown #(reset! shutdown-called? true)})
          broken-service    (service :broken-service
                                     {:depends  []
                                      :provides [shutdown]}
                                     {:shutdown #(throw (RuntimeException. "dangit"))})
          app               (bootstrap-services-with-empty-config [(test-service) (broken-service)])]
      (is (false? @shutdown-called?))
      (with-test-logging
        (shutdown!)
        (is (logged? #"Encountered error during shutdown sequence" :error)))
      (is (true? @shutdown-called?))))

  (testing "`core/run-app` runs the framework (blocking until shutdown signal received), and `request-shutdown` shuts down services"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  []
                                      :provides [shutdown]}
                                     {:shutdown #(reset! shutdown-called? true)})
          app               (bootstrap-services-with-empty-config [(test-service)])
          request-shutdown  (get-service-fn app :shutdown-service :request-shutdown)
          main-thread       (future (trapperkeeper/run-app app))]
      (is (false? @shutdown-called?))
      (request-shutdown)
      (deref main-thread)
      (is (true? @shutdown-called?))))

  (testing "`shutdown-on-error` causes services to be shut down and the error is rethrown from main"
    (let [shutdown-called?  (atom false)
          test-service      (service :test-service
                                     {:depends  [[:shutdown-service shutdown-on-error]]
                                      :provides [broken-fn shutdown]}
                                     {:shutdown  #(reset! shutdown-called? true)
                                      :broken-fn (fn [] (future (shutdown-on-error #(throw (RuntimeException. "oops")))))})
          app                (bootstrap-services-with-empty-config [(test-service)])
          broken-fn          (get-service-fn app :test-service :broken-fn)
          main-thread        (future (trapperkeeper/run-app app))]
      (is (false? @shutdown-called?))
      (broken-fn)
      (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"java.lang.RuntimeException: oops"
            (deref main-thread)))
      (is (true? @shutdown-called?))))

  (testing "`shutdown-on-error` takes an optional function that is called on error"
    (let [shutdown-called?    (atom false)
          on-error-fn-called? (atom false)
          broken-service      (service :broken-service
                                       {:depends  [[:shutdown-service shutdown-on-error]]
                                        :provides [broken-fn shutdown]}
                                       {:shutdown  #(reset! shutdown-called? true)
                                        :broken-fn (fn [] (shutdown-on-error #(throw (RuntimeException. "uh oh"))
                                                                             #(reset! on-error-fn-called? true)))})
          app                 (bootstrap-services-with-empty-config [(broken-service)])
          broken-fn           (get-service-fn app :broken-service :broken-fn)
          main-thread         (future (trapperkeeper/run-app app))]
      (is (false? @shutdown-called?))
      (is (false? @on-error-fn-called?))
      (broken-fn)
      (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"java.lang.RuntimeException: uh oh"
            (deref main-thread)))
      (is (true? @shutdown-called?))
      (is (true? @on-error-fn-called?))))

  (testing "errors thrown by the `shutdown-on-error` optional on-error function are caught and logged"
    (let [broken-service  (service :broken-service
                                   {:depends  [[:shutdown-service shutdown-on-error]]
                                    :provides [broken-fn]}
                                   {:broken-fn (fn [] (shutdown-on-error #(throw (RuntimeException. "unused"))
                                                                         #(throw (RuntimeException. "catch me"))))})
          app             (bootstrap-services-with-empty-config [(broken-service)])
          broken-fn       (get-service-fn app :broken-service :broken-fn)]
      (with-test-logging
        (let [main-thread (future (trapperkeeper/run-app app))]
          (broken-fn)
          ;; main will rethrow the "unused" exception as expected
          ;; so we need to prevent that from failing the test
          (try (deref main-thread) (catch Throwable t))
          (is (logged? #"Error occurred during shutdown" :error)))))))
