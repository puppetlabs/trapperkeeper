(ns puppetlabs.trapperkeeper.services-test
  (:require [clojure.test :refer :all]
            [plumbing.fnk.pfnk :as pfnk]
            [puppetlabs.trapperkeeper.services :refer :all]
            [puppetlabs.trapperkeeper.app :refer [service-graph?]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-empty-config bootstrap-framework-with-no-services]]))

(deftest defservice-macro
  (def logging-service
    (service :logging-service
      {:depends  []
       :provides [log]}
      {:log (fn [msg] "do nothing")}))

  (defservice simple-service
    "My simple service"
    {:depends  [[:logging-service log]]
     :provides [hello]}
    ;; this line is just here to test support for multi-form service bodies
    (log "Calling our test log function!")
    (let [state "world"]
      {:hello (fn [] state)}))

  (testing "service has the correct form"
    (is (= (:doc (meta #'simple-service)) "My simple service"))

    (let [service-graph (simple-service)]
      (is (map? service-graph))

      (let [graph-keys (keys service-graph)]
        (is (= (count graph-keys) 1))
        (is (= (first graph-keys) :simple-service)))

      (let [service-fnk  (:simple-service service-graph)
            depends      (pfnk/input-schema service-fnk)
            provides     (pfnk/output-schema service-fnk)]
        (is (ifn? service-fnk))
        (is (= depends  {:logging-service {:log true}}))
        (is (= provides {:hello true})))))

  (testing "services compile correctly and can be called"
    (let [app       (bootstrap-services-with-empty-config [(logging-service) (simple-service)])
          hello-fn  (get-service-fn app :simple-service :hello)]
      (is (= (hello-fn) "world")))))

(deftest missing-depends-provides
  (testing "an appropriate exception is thrown when :depends is missing"
    ;; Due to the implementation of defservice we have to
    ;; operate at the compiler level in order to test it.
    (is (thrown-with-msg?
          clojure.lang.Compiler$CompilerException
          #"IllegalArgumentException: :depends is required in service definition"
          (load-string
            "(require '[puppetlabs.trapperkeeper.services :refer [defservice]])
             (defservice broken-service
               {:provides []}
               {})")))
  (testing "an appropriate exception is thrown when :provides is missing"
    (is (thrown-with-msg?
          IllegalArgumentException
          #":provides is required in service definition"
          (load-string
            "(require '[puppetlabs.trapperkeeper.services :refer [service]])
             (service :broken-service
                      {:depends []}
                      {})"))))))

(deftest missing-service
  (testing "`get-service-fn` throws a useful error message if the service or service fn does not exist"
    (is (thrown-with-msg?
          IllegalArgumentException
          #"(?s)^.*Service .* not found in graph.*$"
          (->
            (bootstrap-framework-with-no-services)
            (get-service-fn :service :service-fn))))))