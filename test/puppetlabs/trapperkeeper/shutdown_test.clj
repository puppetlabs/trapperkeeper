(ns puppetlabs.trapperkeeper.shutdown-test
  (:import (java.util.concurrent ExecutionException))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.app :refer [app-context
                                                  check-for-errors!
                                                  get-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.services :refer [service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-empty-config]]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
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
      (internal/shutdown! (app-context app))
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
      (internal/shutdown! (app-context app))
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
      (logging/with-test-logging
        (internal/shutdown! (app-context app))
        (is (logged? #"Encountered error during shutdown sequence" :error)))
      (is (true? @shutdown-called?))))

  (testing "`tk/run-app` runs the framework (blocking until shutdown signal received), and `request-shutdown` shuts down services"
    (let [shutdown-called?  (atom false)
          test-service      (service []
                                     (stop [this context]
                                           (reset! shutdown-called? true)
                                           context))
          app               (bootstrap-services-with-empty-config [test-service])
          shutdown-svc      (get-service app :ShutdownService)
          main-thread       (future (tk/run-app app))]
      (is (false? @shutdown-called?))
      (internal/request-shutdown shutdown-svc)
      (deref main-thread)
      (is (true? @shutdown-called?))))

  (testing (str "`shutdown-on-error` in custom function causes services to be "
                "shut down and the error is rethrown from main")
    (let [shutdown-called?  (atom false)
          test-service      (service ShutdownTestServiceWithFn
                                     [[:ShutdownService shutdown-on-error]]
                                     (stop [this context]
                                           (reset! shutdown-called? true)
                                           context)
                                     (test-fn [this]
                                              (future (shutdown-on-error
                                                        (service-id this)
                                                        #(throw
                                                          (Throwable.
                                                            "oops"))))))
          app               (bootstrap-services-with-empty-config
                              [test-service]
                              true)
          test-svc          (get-service app :ShutdownTestServiceWithFn)
          main-thread       (future (tk/run-app app))]
      (is (false? @shutdown-called?))
      (test-fn test-svc)
      (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"java.lang.Throwable: oops"
            (deref main-thread)))
      (is (true? @shutdown-called?))))

  (defn bootstrap-and-validate-shutdown
    [services shutdown-called? expected-exception-message]
    (let [app         (bootstrap-services-with-empty-config services true)
          main-thread (future (tk/run-app app))]
        (is (thrown-with-msg?
              ExecutionException
              expected-exception-message
              (deref main-thread))
            "tk run-app did not die with expected exception.")
        (is (true? @shutdown-called?)
            "Service shutdown was not called.")))

  (testing (str "shutdown will be called if a service throws an exception "
                "during init on main thread, with appropriate errors logged")
    (logging/with-test-logging
      (let [shutdown-called? (atom false)
            test-service     (service ShutdownTestService
                                      [[:ShutdownService shutdown-on-error]]
                                      (init [this context]
                                            (throw (Throwable. "oops"))
                                            context)
                                      (stop [this context]
                                            (reset! shutdown-called? true)
                                            context))]
        (bootstrap-and-validate-shutdown
          [test-service]
          shutdown-called?
          #"java.lang.Throwable")
        (is (logged? #"Error during service init!!!" :error)
            "Error message for service init not logged."))))

  (testing (str "shutdown will be called if a service throws an exception "
                "during start on main thread, with appropriate errors logged")
    (logging/with-test-logging
      (let [shutdown-called?  (atom false)
            test-service      (service ShutdownTestService
                                       [[:ShutdownService shutdown-on-error]]
                                       (start [this context]
                                              (throw (Throwable. "oops"))
                                              context)
                                       (stop  [this context]
                                              (reset! shutdown-called? true)
                                              context))]
        (bootstrap-and-validate-shutdown
          [test-service]
          shutdown-called?
          #"java.lang.Throwable")
        (is (logged? #"Error during service start!!!" :error)
            "Error message for service start not logged."))))

  (testing (str "`shutdown-on-error` will catch and log errors raised during "
                "init on main thread")
    (logging/with-test-logging
      (let [shutdown-called?  (atom false)
            test-service      (service ShutdownTestService
                                       [[:ShutdownService shutdown-on-error]]
                                       (init [this context]
                                             (shutdown-on-error
                                               :ShutdownTestService
                                               #(throw (Throwable. "oops")))
                                             context)
                                       (stop [this context]
                                             (reset! shutdown-called? true)
                                             context))]
        (bootstrap-and-validate-shutdown
          [test-service]
          shutdown-called?
          #"java.lang.Throwable")
        (is (logged? #"shutdown-on-error triggered because of exception!"
                     :error)
            "Error message for shutdown-on-error not logged."))))

  (testing (str "`shutdown-on-error` will catch and log errors raised during "
                "init on future")
    (logging/with-test-logging
      (let [shutdown-called?  (atom false)
            test-service      (service ShutdownTestService
                                       [[:ShutdownService shutdown-on-error]]
                                       (init [this context]
                                             @(future
                                               (shutdown-on-error
                                                 :ShutdownTestService
                                                 #(throw (Throwable. "oops"))))
                                             context)
                                       (stop [this context]
                                             (reset! shutdown-called? true)
                                             context))]
        (bootstrap-and-validate-shutdown
          [test-service]
          shutdown-called?
          #"java.lang.Throwable")
        (is (logged? #"shutdown-on-error triggered because of exception!"
                     :error)
            "Error message for shutdown-on-error not logged."))))

  (testing (str "`shutdown-on-error` will catch and log errors raised during"
                "start on main thread")
    (logging/with-test-logging
      (let [shutdown-called?  (atom false)
            test-service      (service ShutdownTestService
                                       [[:ShutdownService shutdown-on-error]]
                                       (start [this context]
                                             (shutdown-on-error
                                               :ShutdownTestService
                                               #(throw (Throwable. "oops")))
                                             context)
                                       (stop [this context]
                                             (reset! shutdown-called? true)
                                             context))]
        (bootstrap-and-validate-shutdown
          [test-service]
          shutdown-called?
          #"java.lang.Throwable")
        (is (logged? #"shutdown-on-error triggered because of exception!"
                     :error)
            "Error message for shutdown-on-error not logged."))))

  (testing (str "`shutdown-on-error` will catch and log errors raised during "
                "start on future")
    (logging/with-test-logging
      (let [shutdown-called?  (atom false)
            test-service      (service ShutdownTestService
                                       [[:ShutdownService shutdown-on-error]]
                                       (start [this context]
                                              @(future
                                                 (shutdown-on-error
                                                   :ShutdownTestService
                                                   #(throw (Throwable. "oops"))))
                                             context)
                                       (stop [this context]
                                             (reset! shutdown-called? true)
                                             context))]
        (bootstrap-and-validate-shutdown
          [test-service]
          shutdown-called?
          #"java.lang.Throwable")
        (is (logged? #"shutdown-on-error triggered because of exception!"
                     :error)
            "Error message for shutdown-on-error not logged."))))

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
          test-svc            (get-service app :ShutdownTestServiceWithFn)
          main-thread         (future (tk/run-app app))]
      (is (false? @shutdown-called?))
      (is (false? @on-error-fn-called?))
      (test-fn test-svc)
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
                                                               #(throw (Throwable. "foo"))
                                                               (fn [ctxt] (throw (Throwable. "busted on-error function"))))))
          app             (bootstrap-services-with-empty-config [broken-service])
          test-svc        (get-service app :ShutdownTestServiceWithFn)]
      (logging/with-test-logging
        (let [main-thread (future (tk/run-app app))]
          (test-fn test-svc)
          (is (thrown-with-msg?
                java.util.concurrent.ExecutionException #"java.lang.Throwable: foo"
                (deref main-thread)))
          (is (logged? #"Error occurred during shutdown" :error)))))))

(deftest shutdown-on-error-error-handling
  (testing "Shutdown-on-error should never throw an exception."
    (testing "providing `nil` for all arguments"
      (let [test-service (tk/service
                           [[:ShutdownService shutdown-on-error]]
                           (init [this context]
                                 (shutdown-on-error nil nil nil)
                                 context))]
        (is (not (nil? (bootstrap-services-with-empty-config [test-service]
                                                             true))))))

    (testing "passing `nil` instead of a function"
      (let [test-service (tk/service
                           [[:ShutdownService shutdown-on-error]]
                           (init [this context]
                                 (shutdown-on-error context nil)
                                 context))]
        (is (not (nil? (bootstrap-services-with-empty-config [test-service]
                                                             true))))))))

(deftest app-check-for-errors!-tests
  (testing "check-for-errors! throws exception for shutdown-on-error in init"
    (let [test-service     (service ShutdownTestService
                                    [[:ShutdownService shutdown-on-error]]
                                    (init [this context]
                                          (shutdown-on-error
                                            :ShutdownTestService
                                            #(throw (Throwable. "oops")))
                                          context))
          app              (bootstrap-services-with-empty-config
                             [test-service]
                             true)]
      (is (thrown-with-msg?
            Throwable
            #"oops"
            (check-for-errors! app))
          "Expected error not thrown for check-for-errors!")))
  (testing "check-for-errors! throws exception for error in init"
    (let [test-service     (service ShutdownTestService
                                    [[:ShutdownService shutdown-on-error]]
                                    (init [this context]
                                          (throw (Throwable. "oops"))
                                          context))
          app              (bootstrap-services-with-empty-config
                             [test-service]
                             true)]
      (is (thrown-with-msg?
            Throwable
            #"oops"
            (check-for-errors! app))
          "Expected error not thrown for check-for-errors!")))
  (testing "check-for-errors! throws exception for shutdown-on-error in start"
    (let [test-service     (service ShutdownTestService
                                    [[:ShutdownService shutdown-on-error]]
                                    (start [this context]
                                          (shutdown-on-error
                                            :ShutdownTestService
                                            #(throw (Throwable. "oops")))
                                          context))
          app              (bootstrap-services-with-empty-config
                             [test-service]
                             true)]
      (is (thrown-with-msg?
            Throwable
            #"oops"
            (check-for-errors! app))
          "Expected error not thrown for check-for-errors!")))
  (testing "check-for-errors! throws exception for error in start"
    (let [test-service     (service ShutdownTestService
                                    [[:ShutdownService shutdown-on-error]]
                                    (start [this context]
                                          (throw (Throwable. "oops"))
                                          context))
          app              (bootstrap-services-with-empty-config
                             [test-service]
                             true)]
      (is (thrown-with-msg?
            Throwable
            #"oops"
            (check-for-errors! app))
          "Expected error not thrown for check-for-errors!")))
  (testing "check-for-errors! returns app when no shutdown-on-error occurs"
    (let [test-service     (service ShutdownTestService
                                    [[:ShutdownService shutdown-on-error]]
                                    (init [this context]
                                          context))
          app              (bootstrap-services-with-empty-config
                             [test-service]
                             true)]
      (is (identical? app (check-for-errors! app))
          "app not returned for check-for-errors!"))))