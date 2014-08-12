(ns puppetlabs.trapperkeeper.services-internal-test
  (:import (clojure.lang IFn))
  (:require [clojure.test :refer :all]
            [plumbing.fnk.pfnk :as pfnk]
            [schema.core :as schema]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.app :as app]
            [puppetlabs.trapperkeeper.services :refer [service service-map] :as svcs]
            [puppetlabs.trapperkeeper.services-internal :as si]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer
             [with-app-with-empty-config]]))

(use-fixtures :once schema-test/validate-schemas)

(deftest service-forms-test
  (testing "should support forms that include protocol"
    (is (= {:dependencies         []
            :fns                  '()
            :service-protocol-sym 'Foo}
           (si/find-prot-and-deps-forms! '(Foo [])))))
  (testing "should support forms that do not include protocol"
    (is (= {:dependencies         []
            :fns                  '()
            :service-protocol-sym nil}
           (si/find-prot-and-deps-forms! '([])))))
  (testing "result should include vector of fn forms if provided"
    (is (= {:dependencies         []
            :fns                  '((fn1 [] "fn1") (fn2 [] "fn2"))
            :service-protocol-sym 'Foo}
           (si/find-prot-and-deps-forms!
             '(Foo [] (fn1 [] "fn1") (fn2 [] "fn2")))))
    (is (= {:dependencies         []
            :fns                  '((fn1 [] "fn1") (fn2 [] "fn2"))
            :service-protocol-sym nil}
           (si/find-prot-and-deps-forms!
             '([] (fn1 [] "fn1") (fn2 [] "fn2"))))))
  (testing "should throw exception if the first form is not the protocol symbol or dependency vector"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Invalid service definition; first form must be protocol or dependency list; found '\"hi\"'"
          (si/find-prot-and-deps-forms! '("hi" [])))))
  (testing "should throw exception if the first form is a protocol sym and the second is not a dependency vector"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Invalid service definition; expected dependency list following protocol, found: '\"hi\"'"
          (si/find-prot-and-deps-forms! '(Foo "hi")))))
  (testing "should throw an exception if all remaining forms are not seqs"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Invalid service definition; expected function definitions following dependency list, invalid value: '\"hi\"'"
          (si/find-prot-and-deps-forms! '(Foo [] (fn1 [] "fn1") "hi"))))))

(defn local-resolve
  "Resolve symbol in current (services-internal-test) namespace"
  [sym]
  {:pre [(symbol? sym)]}
  (ns-resolve
    'puppetlabs.trapperkeeper.services-internal-test
    sym))

(defprotocol EmptyProtocol)
(def NonProtocolSym "hi")

(deftest protocol-syms-test
  (testing "should not throw exception if protocol exists"
    (is (si/protocol?
          (si/validate-protocol-sym!
            'EmptyProtocol
            (local-resolve 'EmptyProtocol)))))

  (testing "should throw exception if service protocol sym is not resolvable"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Unrecognized service protocol 'UndefinedSym'"
          (si/validate-protocol-sym! 'UndefinedSym (local-resolve 'UndefinedSym)))))

  (testing "should throw exception if service protocol symbol is resolveable but does not resolve to a protocol"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Specified service protocol 'NonProtocolSym' does not appear to be a protocol!"
          (si/validate-protocol-sym! 'NonProtocolSym (local-resolve 'NonProtocolSym))))))

(deftest build-fns-map-test
  (testing "minimal services may not define functions other than lifecycle functions"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service attempts to define function 'foo', but does not provide protocol"
          (si/build-fns-map! nil [] ['init 'start]
            '((init [this context] context)
              (start [this context] context)
              (foo [this] "foo")))))))

(defprotocol Service1
  (service1-fn [this]))

(defprotocol Service2
  (service2-fn [this]))

(defprotocol BadServiceProtocol
  (start [this]))

(deftest invalid-fns-test
  (testing "should throw an exception if there is no definition of a function in the protocol"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service does not define function 'service1-fn', which is required by protocol 'Service1'"
          (si/parse-service-forms!
            ['init 'start]
            (cons 'puppetlabs.trapperkeeper.services-internal-test/Service1
              '([] (init [this context] context)))))))
  (testing "should throw an exception if there is a definition for a function that is not in the protocol"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service attempts to define function 'foo', which does not exist in protocol 'Service1'"
          (si/parse-service-forms!
            ['init 'start]
            (cons 'puppetlabs.trapperkeeper.services-internal-test/Service1
                  '([] (foo [this] "foo")))))))
  (testing "should throw an exception if the protocol includes a function with the same name as a lifecycle function"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"Service protocol 'BadServiceProtocol' includes function named 'start', which conflicts with lifecycle function by same name"
          (si/parse-service-forms!
            ['init 'start]
            (cons 'puppetlabs.trapperkeeper.services-internal-test/BadServiceProtocol
                  '([] (start [this] "foo"))))))))

(deftest prismatic-functionality-test
  (testing "prismatic fnk is initialized properly"
    (let [service1  (service Service1
                       []
                       (init [this context] context)
                       (start [this context] context)
                       (service1-fn [this] "Foo!"))
          service2  (service Service2
                       [[:Service1 service1-fn]]
                       (init [this context] context)
                       (start [this context] context)
                       (service2-fn [this] "Bar!"))
          s1-graph  (service-map service1)
          s2-graph  (service-map service2)]
      (is (map? s1-graph))
      (let [graph-keys (keys s1-graph)]
        (is (= (count graph-keys) 1))
        (is (= (first graph-keys) :Service1)))

      (let [service-fnk  (:Service1 s1-graph)
            depends      (pfnk/input-schema service-fnk)
            provides     (pfnk/output-schema service-fnk)]
        (is (ifn? service-fnk))
        (is (= depends  {schema/Keyword schema/Any
                         :tk-app-context schema/Any
                         :tk-service-refs schema/Any}))
        (is (= provides {:service1-fn IFn})))

      (is (map? s2-graph))
      (let [graph-keys (keys s2-graph)]
        (is (= (count graph-keys) 1))
        (is (= (first graph-keys) :Service2)))

      (let [service-fnk  (:Service2 s2-graph)
            depends      (pfnk/input-schema service-fnk)
            provides     (pfnk/output-schema service-fnk)
            fnk-instance (service-fnk {:Service1 {:service1-fn identity}
                                       :tk-app-context (atom {})
                                       :tk-service-refs (atom {})})
            s2-fn        (:service2-fn fnk-instance)]
        (is (ifn? service-fnk))
        (is (= depends {schema/Keyword schema/Any
                        :tk-app-context schema/Any
                        :tk-service-refs schema/Any
                        :Service1 {schema/Keyword schema/Any
                                   :service1-fn   schema/Any}}))
        (is (= provides {:service2-fn IFn}))
        (is (= "Bar!" (s2-fn)))))))

(defprotocol EmptyService)

(deftest explicit-service-symbol-test
  (testing "can explicitly pass `service` a service symbol via internal API"
    (let [empty-service (service {:service-symbol foo/bar} EmptyService [])]
      (with-app-with-empty-config app [empty-service]
        (let [svc (app/get-service app :EmptyService)]
          (is (= :EmptyService (svcs/service-id svc)))
          (is (= (symbol "foo" "bar") (svcs/service-symbol svc))))))))