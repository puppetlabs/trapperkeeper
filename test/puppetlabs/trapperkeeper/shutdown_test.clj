(ns puppetlabs.trapperkeeper.shutdown-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.internal :refer :all]
            [puppetlabs.trapperkeeper.app :refer [app-context get-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-empty-config]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [puppetlabs.kitchensink.testutils.fixtures :refer [with-no-jvm-shutdown-hooks]]))

(use-fixtures :once with-no-jvm-shutdown-hooks)

(defprotocol ShutdownTestService)

(defprotocol ShutdownTestServiceWithFn
  (test-fn [this]))

(deftest shutdown-test
  (testing "service with shutdown hook gets called during shutdown"
    (let [shutdown-called?  (atom false)
          test-service      (service []
                                     (stop [this context]
                                           (reset! shutdown-called? true)
                                           context))
          app               (bootstrap-services-with-empty-config [test-service])]
      (is (false? @shutdown-called?))
      (shutdown! (app-context app))
      (is (true? @shutdown-called?))))

  (testing "services are shut down in dependency order"
    (let [order       (atom [])
          service1    (service ShutdownTestService
                               []
                               (stop [this context]
                                     (swap! order conj 1)
                                     context))
          service2    (service [[:ShutdownTestService]]
                               (stop [this context]
                                     (swap! order conj 2)
                                     context))
          app         (bootstrap-services-with-empty-config [service1 service2])]
      (is (empty? @order))
      (shutdown! (app-context app))
      (is (= @order [2 1]))))

  (testing "services continue to shut down when one throws an exception"
    (let [shutdown-called?  (atom false)
          test-service      (service []
                                     (stop [this context]
                                           (reset! shutdown-called? true)
                                           context))
          broken-service    (service []
                                     (stop [this context]
                                           (throw (RuntimeException. "dangit"))))
          app               (bootstrap-services-with-empty-config [test-service broken-service])]
      (is (false? @shutdown-called?))
      (with-test-logging
        (shutdown! (app-context app))
        (is (logged? #"Encountered error during shutdown sequence" :error)))
      (is (true? @shutdown-called?))))

  (testing "`tk/run-app` runs the framework (blocking until shutdown signal received), and `request-shutdown` shuts down services"
    (let [shutdown-called?  (atom false)
          test-service      (service []
                                     (stop [this context]
                                           (reset! shutdown-called? true)
                                           context))
          app               (bootstrap-services-with-empty-config [test-service])
          request-shutdown  (partial request-shutdown (get-service app :ShutdownService))
          main-thread       (future (tk/run-app app))]
      (is (false? @shutdown-called?))
      (request-shutdown)
      (deref main-thread)
      (is (true? @shutdown-called?))))

  (testing "`shutdown-on-error` causes services to be shut down and the error is rethrown from main"
    (let [shutdown-called?  (atom false)
          test-service      (service ShutdownTestServiceWithFn
                                     [[:ShutdownService shutdown-on-error]]
                                     (stop [this context]
                                           (reset! shutdown-called? true)
                                           context)
                                     (test-fn [this]
                                              (future (shutdown-on-error (service-id this)
                                                                         #(throw (RuntimeException. "oops"))))))
          app                (bootstrap-services-with-empty-config [test-service])
          broken-fn          (partial test-fn (get-service app :ShutdownTestServiceWithFn))
          main-thread        (future (tk/run-app app))]
      (is (false? @shutdown-called?))
      (broken-fn)
      (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"java.lang.RuntimeException: oops"
            (deref main-thread)))
      (is (true? @shutdown-called?))))

  (testing "`shutdown-on-error` takes an optional function that is called on error"
    (let [shutdown-called?    (atom false)
          on-error-fn-called? (atom false)
          broken-service      (service ShutdownTestServiceWithFn
                                       [[:ShutdownService shutdown-on-error]]
                                       (stop [this context]
                                             (reset! shutdown-called? true)
                                             context)
                                       (test-fn [this]
                                                (shutdown-on-error (service-id this)
                                                                   #(throw (RuntimeException. "uh oh"))
                                                                   (fn [ctxt] (reset! on-error-fn-called? true)))))
          app                 (bootstrap-services-with-empty-config [broken-service])
          broken-fn           (partial test-fn (get-service app :ShutdownTestServiceWithFn))
          main-thread         (future (tk/run-app app))]
      (is (false? @shutdown-called?))
      (is (false? @on-error-fn-called?))
      (broken-fn)
      (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"java.lang.RuntimeException: uh oh"
            (deref main-thread)))
      (is (true? @shutdown-called?))
      (is (true? @on-error-fn-called?))))

  (testing "errors thrown by the `shutdown-on-error` optional on-error function are caught and logged"
    (let [broken-service  (service ShutdownTestServiceWithFn
                                   [[:ShutdownService shutdown-on-error]]
                                   (test-fn [this]
                                            (shutdown-on-error (service-id this)
                                                               #(throw (RuntimeException. "unused"))
                                                               (fn [ctxt] (throw (RuntimeException. "catch me"))))))
          app             (bootstrap-services-with-empty-config [broken-service])
          broken-fn       (partial test-fn (get-service app :ShutdownTestServiceWithFn))]
      (with-test-logging
        (let [main-thread (future (tk/run-app app))]
          (broken-fn)
          ;; main will rethrow the "unused" exception as expected
          ;; so we need to prevent that from failing the test
          (try (deref main-thread) (catch Throwable t))
          (is (logged? #"Error occurred during shutdown" :error)))))))
