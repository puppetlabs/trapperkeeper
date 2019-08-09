(ns puppetlabs.trapperkeeper.services-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services :refer
             [defservice service] :as svcs]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer
             [bootstrap-services-with-empty-config
              with-app-with-empty-config
              with-app-with-config]]
            [schema.test :as schema-test]
            [puppetlabs.kitchensink.testutils.fixtures :refer [with-no-jvm-shutdown-hooks]]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.kitchensink.testutils :as ks-testutils]
            [me.raynes.fs :as fs])
  (:import (java.util.concurrent ExecutionException)))

(use-fixtures :once schema-test/validate-schemas with-no-jvm-shutdown-hooks)

(defprotocol EmptyService)

(defprotocol HelloService
  (hello [this msg]))

(defservice hello-service
  HelloService
  []
  (init [this context] context)
  (start [this context] context)
  (hello [this msg] (str "HELLO!: " msg)))

(deftest test-satisfies-protocols
  (testing "creates a service definition"
    (is (satisfies? svcs/ServiceDefinition hello-service)))

  (let [app (bootstrap-services-with-empty-config [hello-service])]
    (testing "app satisfies protocol"
      (is (satisfies? app/TrapperkeeperApp app)))

    (let [h-s (app/get-service app :HelloService)]
      (testing "service satisfies all protocols"
        (is (satisfies? svcs/Lifecycle h-s))
        (is (satisfies? svcs/Service h-s))
        (is (satisfies? HelloService h-s)))

      (testing "service functions behave as expected"
        (is (= "HELLO!: yo" (hello h-s "yo")))))))

(defprotocol Service1
  (service1-fn [this]))

(defprotocol Service2
  (service2-fn [this]))

(defprotocol Service3
  (service3-fn [this]))

(deftest test-services-not-required
  (testing "services are not required to define lifecycle functions"
    (let [service1 (service Service1
                     []
                     (service1-fn [this] "hi"))
          app (bootstrap-services-with-empty-config [service1])]
      (is (not (nil? app))))))

(deftest test-restart-file-correct-count
  (testing "create a restart file and check that it correctly increments when a service starts"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "hi"))
          temp-file (kitchensink/temp-file-name "counter")]
          (with-app-with-config app [service1] {:global {:restart-file temp-file}}
            (is (= (slurp temp-file) "1"))))))

(deftest test-restart-file-HUP-restart
  (testing "check that restart file correctly increments on HUP restarts"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "hi"))
          temp-file (kitchensink/temp-file-name "counter")]
      (with-app-with-config app [service1] {:global {:restart-file temp-file}}
        (app/restart app)
        (is (= (slurp temp-file) "2"))))))

(deftest test-restart-file-big-int-limit
  (testing "restart file should reset to 1 if the counter is too large"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "hi"))
          temp-file (kitchensink/temp-file-name "counter")]
      (spit temp-file "9223372036854775807")
      (with-app-with-config app [service1] {:global {:restart-file temp-file}}
        (is (= (slurp temp-file) "1")))
      (with-app-with-config app [service1] {:global {:restart-file temp-file}}
        (is (= (slurp temp-file) "2"))))))

(deftest test-invalid-restart-file
  (testing "restart file should reset to 1 if the file is unparseable"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "hi"))
          temp-file (kitchensink/temp-file-name "counter")]
      (spit temp-file "hello")
      (with-app-with-config app [service1] {:global {:restart-file temp-file}}
        (is (= (slurp temp-file) "1"))))))

(deftest test-restart-file-no-permissions
  (testing "exception should be thrown if restart file is not readable/writeable"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "hi"))
          temp-file (kitchensink/temp-file-name "counter")]
      (fs/chmod "u-rw" (fs/touch temp-file))
      (is (thrown? IllegalStateException
                   (with-app-with-config app [service1] {:global {:restart-file temp-file}}))))))

(deftest test-restart-file-missing-parent-dirs
  (testing "some portion of the parent directory structure does not exist"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "hi"))
          temp-file (fs/file (kitchensink/temp-dir "foo") "bar" "baz" "counter")]
      (is (false? (fs/exists? (fs/parent temp-file))))
      (is (false? (fs/exists? temp-file)))
      (with-app-with-config app [service1] {:global {:restart-file temp-file}}
        (is (= (slurp temp-file) "1"))))))

(defn create-lifecycle-services
  [call-seq]

  (let [lc-fn (fn [context action] (swap! call-seq conj action) context)]
    [(service
       Service1
       []
       (init [this context] (lc-fn context :init-service1))
       (start [this context] (lc-fn context :start-service1))
       (stop [this context] (lc-fn context :stop-service1))
       (service1-fn [this] (lc-fn nil :service1-fn)))

     (service
       Service2
       [[:Service1 service1-fn]]
       (init [this context] (lc-fn context :init-service2))
       (start [this context] (lc-fn context :start-service2))
       (stop [this context] (lc-fn context :stop-service2))
       (service2-fn [this] (lc-fn nil :service2-fn)))

     (service
       Service3
       [[:Service2 service2-fn]]
       (init [this context] (lc-fn context :init-service3))
       (start [this context] (lc-fn context :start-service3))
       (stop [this context] (lc-fn context :stop-service3))
       (service3-fn [this] (lc-fn nil :service3-fn)))]))

(deftest test-lifecycle-functions-ordered-correctly
  (testing "life cycle functions are called in the correct order"
    (let [call-seq (atom [])
          services (create-lifecycle-services call-seq)]
      (with-app-with-empty-config app
        services
        (is (= [:init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3]
               @call-seq)))
      (is (= [:init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3
              :stop-service3 :stop-service2 :stop-service1]
             @call-seq))


      (reset! call-seq [])
      (with-app-with-empty-config app
        services
        (is (= [:init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3]
               @call-seq)))
      (is (= [:init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3
              :stop-service3 :stop-service2 :stop-service1]
             @call-seq)))))

(deftest test-lifecycle-function-ordering-restart
  (testing "app restart calls life cycle functions in the correct order"
    (let [call-seq (atom [])
          services (create-lifecycle-services call-seq)]
      (with-app-with-empty-config app
        services
        (is (= [:init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3]
               @call-seq))
        (app/restart app)
        (is (= [:init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3
                :stop-service3 :stop-service2 :stop-service1
                :init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3]
               @call-seq)))
      (is (= [:init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3
              :stop-service3 :stop-service2 :stop-service1
              :init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3
              :stop-service3 :stop-service2 :stop-service1]
             @call-seq)))))

(deftest test-lifecycle-function-ordering-signaling
  (testing "app restart calls life cycle functions in the correct order"
    (let [call-seq (atom [])
          services (create-lifecycle-services call-seq)]
      (with-app-with-empty-config app
        services
        (is (= [:init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3]
               @call-seq))
        ;; It would be preferrable to send a HUP here, but jenkins clients
        ;; swallow the HUP signal, which makes the test fail. So instead we
        ;; just call the thing that the signal would have caused to be called.
        (internal/restart-tk-apps [app])
        (let [start (System/currentTimeMillis)]
          (while (and (not= (count @call-seq) 15)
                      (< (- (System/currentTimeMillis) start) 5000))
            (Thread/yield)))
        (is (= (count @call-seq) 15))
        (is (= [:init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3
                :stop-service3 :stop-service2 :stop-service1
                :init-service1 :init-service2 :init-service3
                :start-service1 :start-service2 :start-service3]
               @call-seq)))
      (is (= [:init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3
              :stop-service3 :stop-service2 :stop-service1
              :init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3
              :stop-service3 :stop-service2 :stop-service1]
             @call-seq)))))

(deftest test-context-cleared-on-restart
  (testing "service contexts should be cleared out during a restart"
    (let [test-init-context (atom nil)
          test-init-count (atom 0)
          test-start-context (atom nil)
          context-elem  {:foo "bar"}
          service1 (service EmptyService
                     []
                     (init [this context]
                       (reset! test-init-context (merge context context-elem))
                       (swap! test-init-count inc)
                       {:context @test-init-context :count @test-init-count})
                     (start [this context]
                       (reset! test-start-context context)))]
      (with-app-with-empty-config app [service1]
        (is (= context-elem @test-init-context))
        (is (= {:foo "bar"} (:context @test-start-context)))
        (is (= 1 (:count @test-start-context)))
        (swap! test-init-context dissoc :context)
        (swap! test-start-context dissoc :context)
        (app/restart app))
      (is (= {:foo "bar"} @test-init-context))
      (is (= {:foo "bar"} (:context @test-start-context)))
      (is (= 2 (:count @test-start-context))))))

(deftest test-exception-during-restart
  (testing "restart should halt if an exception is raised"
    (let [call-seq (atom [])
          services (conj (create-lifecycle-services call-seq)
                         (service
                           EmptyService
                           []
                           (stop [this context] (throw (IllegalStateException. "Exploding Service")))))]
      (ks-testutils/with-no-jvm-shutdown-hooks
       ; We can't use the with-app-with-empty-config macro because we don't
       ; want to use its implicit tk-app/stop call. We're asserting that
       ; the stop will happen because of the exception. So instead, we use
       ; the tk/run-app here to block on the app until the restart is
       ; called and explodes in an exception.
        (let [app (internal/throw-app-error-if-exists!
                   (bootstrap-services-with-empty-config services))
              app-running (future (tk/run-app app))]
          (is (= [:init-service1 :init-service2 :init-service3
                  :start-service1 :start-service2 :start-service3]
                 @call-seq))
          (app/restart app)
          (try
            @app-running
            (catch ExecutionException e
              (is (instance? IllegalStateException (.getCause e)))
              (is (= "Exploding Service" (.. e getCause getMessage)))))))
      ; Here we validate that the stop completed but no new init happened
      (is (= [:init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3
              :stop-service3 :stop-service2 :stop-service1]
             @call-seq)))))

(deftest test-lifecycle-service-id-available
  (testing "service-id should be able to be called from any lifecycle phase"
    (let [test-context (atom {})
          service1 (service Service1
                     []
                     (init [this context]
                       (swap! test-context assoc :init-service-id (svcs/service-id this))
                       context)
                     (start [this context]
                       (swap! test-context assoc :start-service-id (svcs/service-id this))
                       context)
                     (stop [this context]
                       (swap! test-context assoc :stop-service-id (svcs/service-id this))
                       context)
                     (service1-fn [this] nil))]
      (with-app-with-empty-config app [service1]
        ;; no-op; we just want the app to start up and shut down
)
      (is (= :Service1 (:init-service-id @test-context)))
      (is (= :Service1 (:start-service-id @test-context)))
      (is (= :Service1 (:stop-service-id @test-context))))))

(deftest dependencies-test
  (testing "services should be able to call functions in dependency list"
    (let [service1 (service Service1
                     []
                     (service1-fn [this] "FOO!"))
          service2 (service Service2
                     [[:Service1 service1-fn]]
                     (service2-fn [this] (str "HELLO " (service1-fn))))
          app (bootstrap-services-with-empty-config [service1 service2])
          s2  (app/get-service app :Service2)]
      (is (= "HELLO FOO!" (service2-fn s2)))))

  (testing "services should be able to retrieve instances of services that they depend on"
    (let [service1 (service Service1
                     []
                     (service1-fn [this] "FOO!"))
          service2 (service Service2
                     [[:Service1 service1-fn]]
                     (init [this context]
                       (let [s1 (svcs/get-service this :Service1)]
                         (assoc context :s1 s1)))
                     (service2-fn [this] ((svcs/service-context this) :s1)))
          app               (bootstrap-services-with-empty-config [service1 service2])
          s2                (app/get-service app :Service2)
          s1                (service2-fn s2)]
      (is (satisfies? Service1 s1))
      (is (= "FOO!" (service1-fn s1)))))

  (testing "an error should be thrown if calling get-service on a non-existent service"
    (let [service1 (service Service1
                     []
                     (service1-fn [this] (svcs/get-service this :NonExistent)))
          app               (bootstrap-services-with-empty-config [service1])
          s1                (app/get-service app :Service1)]
      (is (thrown-with-msg?
           IllegalArgumentException
           #"Call to 'get-service' failed; service ':NonExistent' does not exist."
           (service1-fn s1)))))

  (testing "lifecycle functions should be able to call injected functions"
    (let [service1 (service Service1
                     []
                     (service1-fn [this] "FOO!"))
          service2 (service Service2
                     [[:Service1 service1-fn]]
                     (init [this context] (assoc context
                                                 :injected-fn-result
                                                 (service1-fn)))
                     (service2-fn [this]
                                  ((svcs/service-context this) :injected-fn-result)))
          app (bootstrap-services-with-empty-config [service1 service2])
          s2  (app/get-service app :Service2)]
      (is (= "FOO!" (service2-fn s2))))))

(defprotocol Service4
  (service4-fn1 [this])
  (service4-fn2 [this]))

(deftest service-this-test
  (testing "should be able to call other functions in same service via 'this'"
    (let [service4  (service Service4
                      []
                      (service4-fn1 [this] "foo!")
                      (service4-fn2 [this] (str (service4-fn1 this) " bar!")))
          app       (bootstrap-services-with-empty-config [service4])
          s4        (app/get-service app :Service4)]
      (is (= "foo! bar!" (service4-fn2 s4))))))

(defservice service1
  Service1
  []
  (init [this context] "hi")
  (service1-fn [this] "hi"))

(defservice service1-alt
  Service1
  []
  (start [this context] "hi")
  (service1-fn [this] "hi"))

(deftest context-test
  (testing "should error if lifecycle function doesn't return context"
    (is (thrown-with-msg?
         IllegalStateException
         (re-pattern (str "Lifecycle function 'init' for service "
                          "'puppetlabs.trapperkeeper.services-test/service1'"
                          " must return a context map \\(got: \"hi\"\\)"))
         (bootstrap-services-with-empty-config [service1]))
        "Unexpected shutdown reason for bootstrap")
    (is (thrown-with-msg?
         IllegalStateException
         (re-pattern (str "Lifecycle function 'start' for service "
                          "'puppetlabs.trapperkeeper.services-test/service1-alt'"
                          " must return a context map "
                          "\\(got: \"hi\"\\)"))
         (bootstrap-services-with-empty-config [service1-alt]))
        "Unexpected shutdown reason for bootstrap"))

  (testing "lifecycle error works if service has no service symbol"
    (let [service1 (service Service1
                     []
                     (init [this context] "hi")
                     (service1-fn [this] "hi"))]
      (is (thrown-with-msg?
           IllegalStateException
           (re-pattern (str "Lifecycle function 'init' for service ':Service1'"
                            " must return a context map \\(got: \"hi\"\\)"))
           (bootstrap-services-with-empty-config [service1]))
          "Unexpected shutdown reason for bootstrap"))
    (let [service1 (service Service1
                     []
                     (start [this context] "hi")
                     (service1-fn [this] "hi"))]

      (is (thrown-with-msg?
           IllegalStateException
           (re-pattern (str "Lifecycle function 'start' for service "
                            "':Service1' must return a context map "
                            "\\(got: \"hi\"\\)"))
           (bootstrap-services-with-empty-config [service1]))
          "Unexpected shutdown reason for bootstrap")))

  (testing "context should be available in subsequent lifecycle functions"
    (let [start-context (atom nil)
          service1 (service Service1
                     []
                     (init [this context] (assoc context :foo :bar))
                     (start [this context] (reset! start-context context))
                     (service1-fn [this] "hi"))]
      (bootstrap-services-with-empty-config [service1])
      (is (= {:foo :bar} @start-context))))

  (testing "context should be accessible in service functions"
    (let [sfn-context (atom nil)
          service1 (service Service1
                     []
                     (init [this context] (assoc context :foo :bar))
                     (service1-fn [this] (reset! sfn-context (svcs/service-context this))))
          app (bootstrap-services-with-empty-config [service1])
          s1  (app/get-service app :Service1)]
      (service1-fn s1)
      (is (= {:foo :bar} @sfn-context))
      (is (= {:foo :bar} (svcs/service-context s1)))))

  (testing "context works correctly in injected functions"
    (let [service1 (service Service1
                     []
                     (init [this context] (assoc context :foo :bar))
                     (service1-fn [this] ((svcs/service-context this) :foo)))
          service2 (service Service2
                     [[:Service1 service1-fn]]
                     (service2-fn [this] (service1-fn)))
          app (bootstrap-services-with-empty-config [service1 service2])
          s2  (app/get-service app :Service2)]
      (is (= :bar (service2-fn s2)))))

  (testing "context works correctly in service functions called by other functions in same service"
    (let [service4 (service Service4
                     []
                     (init [this context] (assoc context :foo :bar))
                     (service4-fn1 [this] ((svcs/service-context this) :foo))
                     (service4-fn2 [this] (service4-fn1 this)))
          app (bootstrap-services-with-empty-config [service4])
          s4  (app/get-service app :Service4)]
      (is (= :bar (service4-fn2 s4)))))

  (testing "context from other services should not be visible"
    (let [s2-context (atom nil)
          service1 (service Service1
                     []
                     (init [this context] (assoc context :foo :bar))
                     (service1-fn [this] "hi"))
          service2 (service Service2
                     [[:Service1 service1-fn]]
                     (start [this context] (reset! s2-context (svcs/service-context this)))
                     (service2-fn [this] "hi"))

          app (bootstrap-services-with-empty-config [service1 service2])]
      (is (= {} @s2-context)))))

(deftest service-symbol-test
  (testing "service defined via `defservice` has a service symbol"
    (with-app-with-empty-config app [hello-service]
      (let [svc (app/get-service app :HelloService)]
        (is (= (symbol "puppetlabs.trapperkeeper.services-test" "hello-service")
               (svcs/service-symbol svc))))))
  (testing "service defined via `service` does not have a service symbol"
    (let [empty-svc (service EmptyService [])]
      (with-app-with-empty-config app [empty-svc]
        (let [svc (app/get-service app :EmptyService)]
          (is (= :EmptyService (svcs/service-id svc)))
          (is (nil? (svcs/service-symbol svc))))))))

(deftest get-services-test
  (testing "get-services should return all services"
    (let [empty-service (service EmptyService [])]
      (with-app-with-empty-config app [empty-service hello-service]
        (let [empty (app/get-service app :EmptyService)
              hello (app/get-service app :HelloService)]
          (doseq [s [empty hello]]
            (let [all-services (svcs/get-services s)]
              (is (= 2 (count all-services)))
              (is (every? #(satisfies? svcs/Service %) all-services))
              (is (= #{:EmptyService :HelloService}
                     (set (map svcs/service-id all-services)))))))))))

(deftest minimal-services-test
  (testing "minimal services can be defined without a protocol"
    (let [call-seq (atom [])
          service0 (service []
                     (init [this context]
                       (swap! call-seq conj :init)
                       (assoc context :foo :bar))
                     (start [this context]
                       (swap! call-seq conj :start)
                       (is (= context {:foo :bar}))
                       context))]
      (bootstrap-services-with-empty-config [service0])
      (is (= [:init :start] @call-seq))))

  (testing "minimal services can have dependencies"
    (let [service1 (service Service1
                     []
                     (service1-fn [this] "hi"))
          result   (atom nil)
          service0 (service [[:Service1 service1-fn]]
                     (init [this context]
                       (reset! result (service1-fn))
                       context))]
      (bootstrap-services-with-empty-config [service1 service0])
      (is (= "hi" @result)))))

(defprotocol MultiArityService
  (foo [this x] [this x y]))

(deftest test-multi-arity-protocol-fn
  (testing "should support protocols with multi-arity fns"
    (let [ma-service  (service MultiArityService
                        []
                        (foo [this x] x)
                        (foo [this x y] (+ x y)))
          service1    (service Service1
                        [[:MultiArityService foo]]
                        (service1-fn [this]
                          [(foo 5) (foo 3 6)]))
          app         (bootstrap-services-with-empty-config [ma-service service1])
          mas         (app/get-service app :MultiArityService)
          s1          (app/get-service app :Service1)]
      (is (= 3 (foo mas 3)))
      (is (= 5 (foo mas 4 1)))
      (is (= [5 9] (service1-fn s1))))))

(deftest service-fn-invalid-docstring
  (testing "defining a service function, mistakenly adding a docstring"
    (try (macroexpand '(puppetlabs.trapperkeeper.services/service
                         puppetlabs.trapperkeeper.services-test/Service1
                         []
                         (service1-fn
                           "This is an example of an invalid docstring"
                           [this] nil)))
      (catch Exception e
        (let [cause (-> e Throwable->map :cause)]
          (is (re-matches #"Incorrect macro usage.*" cause)))))))
