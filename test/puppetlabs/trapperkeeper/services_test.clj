(ns puppetlabs.trapperkeeper.services-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services :refer
                [ServiceDefinition Service Lifecycle
                 defservice service service-context]]
            [puppetlabs.trapperkeeper.app :refer [TrapperkeeperApp get-service]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer
                [bootstrap-services-with-empty-config]]))

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
    (satisfies? ServiceDefinition hello-service))

  (let [app (bootstrap-services-with-empty-config [hello-service])]
    (testing "app satisfies protocol"
      (is (satisfies? TrapperkeeperApp app)))

    (let [h-s (get-service app :HelloService)]
      (testing "service satisfies all protocols"
        (is (satisfies? Lifecycle h-s))
        (is (satisfies? Service h-s))
        (is (satisfies? HelloService h-s)))

      (testing "service functions behave as expected"
        (is (= "HELLO!: yo" (hello h-s "yo")))))))

(defprotocol Service1
  (service1-fn [this]))

(defprotocol Service2
  (service2-fn [this]))

(defprotocol Service3
  (service3-fn [this]))

(deftest lifecycle-test
  (testing "services are not required to define lifecycle functions"
    (let [service1  (service Service1
                      []
                      (service1-fn [this] "hi"))
          app       (bootstrap-services-with-empty-config [service1])]
      (is (not (nil? app)))))

  (testing "life cycle functions are called in the correct order"
    (let [call-seq  (atom [])
          lc-fn     (fn [context action] (swap! call-seq conj action) context)
          service1  (service Service1
                      []
                      (init [this context] (lc-fn context :init-service1))
                      (start [this context] (lc-fn context :start-service1))
                      (service1-fn [this] (lc-fn nil :service1-fn)))
          service2  (service Service2
                      [[:Service1 service1-fn]]
                      (init [this context] (lc-fn context :init-service2))
                      (start [this context] (lc-fn context :start-service2))
                      (service2-fn [this] (lc-fn nil :service2-fn)))
          service3  (service Service3
                       [[:Service2 service2-fn]]
                       (init [this context] (lc-fn context :init-service3))
                       (start [this context] (lc-fn context :start-service3))
                       (service3-fn [this] (lc-fn nil :service3-fn)))]
      (bootstrap-services-with-empty-config [service1 service3 service2])
      (is (= [:init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3]
             @call-seq))
      (reset! call-seq [])
      (bootstrap-services-with-empty-config [service3 service2 service1])
      (is (= [:init-service1 :init-service2 :init-service3
              :start-service1 :start-service2 :start-service3]
             @call-seq)))))

(deftest dependencies-test
  (testing "services should be able to call functions in dependency list"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "FOO!"))
          service2 (service Service2
                            [[:Service1 service1-fn]]
                            (service2-fn [this] (str "HELLO " (service1-fn))))
          app (bootstrap-services-with-empty-config [service1 service2])
          s2 (get-service app :Service2)]
      (is (= "HELLO FOO!" (service2-fn s2)))))

  (testing "lifecycle functions should be able to call injected functions"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] "FOO!"))
          service2 (service Service2
                            [[:Service1 service1-fn]]
                            (init [this context] (service1-fn) context)
                            (service2-fn [this] "service2"))
          app (bootstrap-services-with-empty-config [service1 service2])
          s2 (get-service app :Service2)]
      (is (= "service2" (service2-fn s2))))))

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
          s4        (get-service app :Service4)]
      (is (= "foo! bar!" (service4-fn2 s4))))))

(deftest context-test
  (testing "should error if lifecycle function doesn't return context"
    (let [service1 (service Service1
                            []
                            (init [this context] "hi")
                            (service1-fn [this] "hi"))]
      (is (thrown-with-msg?
            IllegalStateException
            #"Lifecycle function 'init' for service ':Service1' must return a context map \(got: \"hi\"\)"
            (bootstrap-services-with-empty-config [service1]))))

    (let [service1 (service Service1
                            []
                            (start [this context] "hi")
                            (service1-fn [this] "hi"))]
      (is (thrown-with-msg?
            IllegalStateException
            #"Lifecycle function 'start' for service ':Service1' must return a context map \(got: \"hi\"\)"
            (bootstrap-services-with-empty-config [service1])))))

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
                            (service1-fn [this] (reset! sfn-context (service-context this))))
          app (bootstrap-services-with-empty-config [service1])
          s1 (get-service app :Service1)]
      (service1-fn s1)
      (is (= {:foo :bar} @sfn-context))
      (is (= {:foo :bar} (service-context s1)))))

  (testing "context works correctly in injected functions"
    (let [service1 (service Service1
                            []
                            (init [this context] (assoc context :foo :bar))
                            (service1-fn [this] ((service-context this) :foo)))
          service2 (service Service2
                            [[:Service1 service1-fn]]
                            (service2-fn [this] (service1-fn)))
          app (bootstrap-services-with-empty-config [service1 service2])
          s2 (get-service app :Service2)]
      (is (= :bar (service2-fn s2)))))

  (testing "context works correctly in service functions called by other functions in same service"
    (let [service4 (service Service4
                            []
                            (init [this context] (assoc context :foo :bar))
                            (service4-fn1 [this] ((service-context this) :foo))
                            (service4-fn2 [this] (service4-fn1 this)))
          app (bootstrap-services-with-empty-config [service4])
          s4 (get-service app :Service4)]
      (is (= :bar (service4-fn2 s4)))))

  (testing "context from other services should not be visible"
    (let [s2-context (atom nil)
          service1 (service Service1
                            []
                            (init [this context] (assoc context :foo :bar))
                            (service1-fn [this] "hi"))
          service2 (service Service2
                            [[:Service1 service1-fn]]
                            (start [this context] (reset! s2-context (service-context this)))
                            (service2-fn [this] "hi"))

          app (bootstrap-services-with-empty-config [service1 service2])]
      (is (= {} @s2-context)))))

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
          mas         (get-service app :MultiArityService)
          s1          (get-service app :Service1)]
      (is (= 3 (foo mas 3)))
      (is (= 5 (foo mas 4 1)))
      (is (= [5 9] (service1-fn s1))))))