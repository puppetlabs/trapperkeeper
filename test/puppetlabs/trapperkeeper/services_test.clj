(ns puppetlabs.trapperkeeper.services-test
  (:import (clojure.lang IFn))
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.services :refer :all]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.testutils.bootstrap
             :refer [bootstrap-services-with-empty-config with-app-with-empty-config]]
            [puppetlabs.kitchensink.testutils.fixtures :refer [with-no-jvm-shutdown-hooks]]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [plumbing.fnk.pfnk :as pfnk]))

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
    (is (satisfies? ServiceDefinition hello-service)))

  (let [app (bootstrap-services-with-empty-config [hello-service])]
    (testing "app satisfies protocol"
      (is (satisfies? app/TrapperkeeperApp app)))

    (let [h-s (app/get-service app :HelloService)]
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
             @call-seq))))

  (testing "service-id should be able to be called from any lifecycle phase"
    (let [test-context (atom {})
          service1 (service Service1
                            []
                            (init [this context]
                                  (swap! test-context assoc :init-service-id (service-id this))
                                  context)
                            (start [this context]
                                   (swap! test-context assoc :start-service-id (service-id this))
                                   context)
                            (stop [this context]
                                  (swap! test-context assoc :stop-service-id (service-id this))
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
                                  (let [s1 (get-service this :Service1)]
                                    (assoc context :s1 s1)))
                            (service2-fn [this] ((service-context this) :s1)))
          app               (bootstrap-services-with-empty-config [service1 service2])
          s2                (app/get-service app :Service2)
          s1                (service2-fn s2)]
      (is (satisfies? Service1 s1))
      (is (= "FOO!" (service1-fn s1)))))

  (testing "an error should be thrown if calling get-service on a non-existent service"
    (let [service1 (service Service1
                            []
                            (service1-fn [this] (get-service this :NonExistent)))
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
                            (init [this context] (service1-fn) context)
                            (service2-fn [this] "service2"))
          app (bootstrap-services-with-empty-config [service1 service2])
          s2  (app/get-service app :Service2)]
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
                            (service1-fn [this] (reset! sfn-context (service-context this))))
          app (bootstrap-services-with-empty-config [service1])
          s1  (app/get-service app :Service1)]
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
          s2  (app/get-service app :Service2)]
      (is (= :bar (service2-fn s2)))))

  (testing "context works correctly in service functions called by other functions in same service"
    (let [service4 (service Service4
                            []
                            (init [this context] (assoc context :foo :bar))
                            (service4-fn1 [this] ((service-context this) :foo))
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
                            (start [this context] (reset! s2-context (service-context this)))
                            (service2-fn [this] "hi"))

          app (bootstrap-services-with-empty-config [service1 service2])]
      (is (= {} @s2-context)))))

(deftest service-symbol-test
  (testing "service defined via `defservice` has a service symbol"
    (with-app-with-empty-config app [hello-service]
      (let [svc (app/get-service app :HelloService)]
        (is (= (symbol "puppetlabs.trapperkeeper.services-test" "hello-service")
               (service-symbol svc))))))
  (testing "service defined via `service` does not have a service symbol"
    (let [empty-svc (service EmptyService [])]
      (with-app-with-empty-config app [empty-svc]
        (let [svc (app/get-service app :EmptyService)]
          (is (= :EmptyService (service-id svc)))
          (is (nil? (service-symbol svc))))))))

(deftest get-services-test
  (testing "get-services should return all services"
    (let [empty-service (service EmptyService [])]
      (with-app-with-empty-config app [empty-service hello-service]
        (let [empty (app/get-service app :EmptyService)
              hello (app/get-service app :HelloService)]
          (doseq [s [empty hello]]
            (let [all-services (get-services s)]
              (is (= 2 (count all-services)))
              (is (every? #(satisfies? Service %) all-services))
              (is (= #{:EmptyService :HelloService}
                     (set (map service-id all-services)))))))))))

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
    (is (thrown-with-msg?
          Exception
          #"Incorrect macro usage"
          (macroexpand '(puppetlabs.trapperkeeper.services/service
                          puppetlabs.trapperkeeper.services-test/Service1
                          []
                          (service1-fn
                            "This is an example of an invalid docstring"
                            [this] nil)))))))


(deftest service-forms-test
  (testing "should support forms that include protocol"
    (is (= {:dependencies         []
            :fns                  '()
            :service-protocol-sym 'Foo}
           (find-prot-and-deps-forms! '(Foo [])))))
  (testing "should support forms that do not include protocol"
    (is (= {:dependencies         []
            :fns                  '()
            :service-protocol-sym nil}
           (find-prot-and-deps-forms! '([])))))
  (testing "result should include vector of fn forms if provided"
    (is (= {:dependencies         []
            :fns                  '((fn1 [] "fn1") (fn2 [] "fn2"))
            :service-protocol-sym 'Foo}
           (find-prot-and-deps-forms!
             '(Foo [] (fn1 [] "fn1") (fn2 [] "fn2")))))
    (is (= {:dependencies         []
            :fns                  '((fn1 [] "fn1") (fn2 [] "fn2"))
            :service-protocol-sym nil}
           (find-prot-and-deps-forms!
             '([] (fn1 [] "fn1") (fn2 [] "fn2"))))))
  (testing "should throw exception if the first form is not the protocol symbol or dependency vector"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Invalid service definition; first form must be protocol or dependency list; found '\"hi\"'"
          (find-prot-and-deps-forms! '("hi" [])))))
  (testing "should throw exception if the first form is a protocol sym and the second is not a dependency vector"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Invalid service definition; expected dependency list following protocol, found: '\"hi\"'"
          (find-prot-and-deps-forms! '(Foo "hi")))))
  (testing "should throw an exception if all remaining forms are not seqs"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Invalid service definition; expected function definitions following dependency list, invalid value: '\"hi\"'"
          (find-prot-and-deps-forms! '(Foo [] (fn1 [] "fn1") "hi"))))))

(defn local-resolve
  "Resolve symbol in current (services-internal-test) namespace"
  [sym]
  {:pre [(symbol? sym)]}
  (ns-resolve
    'puppetlabs.trapperkeeper.services-test
    sym))

(defprotocol EmptyProtocol)
(def NonProtocolSym "hi")

(deftest protocol-syms-test
  (testing "should not throw exception if protocol exists"
    (is (protocol?
          (validate-protocol-sym!
            'EmptyProtocol
            (local-resolve 'EmptyProtocol)))))

  (testing "should throw exception if service protocol sym is not resolvable"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Unrecognized service protocol 'UndefinedSym'"
          (validate-protocol-sym! 'UndefinedSym (local-resolve 'UndefinedSym)))))

  (testing "should throw exception if service protocol symbol is resolveable but does not resolve to a protocol"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Specified service protocol 'NonProtocolSym' does not appear to be a protocol!"
          (validate-protocol-sym! 'NonProtocolSym (local-resolve 'NonProtocolSym))))))

(deftest build-fns-map-test
  (testing "minimal services may not define functions other than lifecycle functions"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service attempts to define function 'foo', but does not provide protocol"
          (build-fns-map! nil [] ['init 'start]
                             '((init [this context] context)
                               (start [this context] context)
                               (foo [this] "foo")))))))

(deftest invalid-fns-test
  (testing "should throw an exception if there is no definition of a function in the protocol"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service does not define function 'service1-fn', which is required by protocol 'Service1'"
          (parse-service-forms!
            ['init 'start]
            (cons 'puppetlabs.trapperkeeper.services-test/Service1
                  '([] (init [this context] context)))))))
  (testing "should throw an exception if there is a definition for a function that is not in the protocol"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service attempts to define function 'foo', which does not exist in protocol 'Service1'"
          (parse-service-forms!
            ['init 'start]
            (cons 'puppetlabs.trapperkeeper.services-test/Service1
                  '([] (foo [this] "foo")))))))
  (testing "should throw an exception if the protocol includes a function with the same name as a lifecycle function"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service protocol 'BadServiceProtocol' includes function named 'start', which conflicts with lifecycle function by same name"
          (parse-service-forms!
            ['init 'start]
            (cons 'puppetlabs.trapperkeeper.testutils.services/BadServiceProtocol
                  '([] (start [this] "foo"))))))))

(deftest prismatic-functionality-test
  (testing "prismatic fnk is initialized properly"
    (let [service1 (service Service1
                            []
                            (init [this context] context)
                            (start [this context] context)
                            (service1-fn [this] "Foo!"))
          service2 (service Service2
                            [[:Service1 service1-fn]]
                            (init [this context] context)
                            (start [this context] context)
                            (service2-fn [this] "Bar!"))
          s1-graph (service-map service1)
          s2-graph (service-map service2)]
      (is (map? s1-graph))
      (let [graph-keys (keys s1-graph)]
        (is (= (count graph-keys) 1))
        (is (= (first graph-keys) :Service1)))

      (let [service-fnk (:Service1 s1-graph)
            depends (pfnk/input-schema service-fnk)
            provides (pfnk/output-schema service-fnk)]
        (is (ifn? service-fnk))
        (is (= depends {schema/Keyword   schema/Any
                        :tk-app-context  schema/Any
                        :tk-service-refs schema/Any}))
        (is (= provides {:service1-fn IFn})))

      (is (map? s2-graph))
      (let [graph-keys (keys s2-graph)]
        (is (= (count graph-keys) 1))
        (is (= (first graph-keys) :Service2)))

      (let [service-fnk (:Service2 s2-graph)
            depends (pfnk/input-schema service-fnk)
            provides (pfnk/output-schema service-fnk)
            fnk-instance (service-fnk {:Service1       {:service1-fn identity}
                                       :tk-app-context (atom {})
                                       :tk-service-refs (atom {})})
            s2-fn (:service2-fn fnk-instance)]
        (is (ifn? service-fnk))
        (is (= depends {schema/Keyword  schema/Any
                        :tk-app-context schema/Any
                        :tk-service-refs schema/Any
                        :Service1       {schema/Keyword schema/Any
                                         :service1-fn   schema/Any}}))
        (is (= provides {:service2-fn IFn}))
        (is (= "Bar!" (s2-fn)))))))

(defprotocol EmptyService)

(deftest explicit-service-symbol-test
  (testing "can explicitly pass `service` a service symbol via internal API"
    (let [empty-service (service {:service-symbol foo/bar} EmptyService [])]
      (with-app-with-empty-config app [empty-service]
                                  (let [svc (app/get-service app :EmptyService)]
                                    (is (= :EmptyService (service-id svc)))
                                    (is (= (symbol "foo" "bar") (service-symbol svc))))))))
