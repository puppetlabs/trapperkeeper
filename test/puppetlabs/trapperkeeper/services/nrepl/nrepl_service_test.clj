(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service-test
  (:require [clojure.test :refer :all]
            [nrepl.core :as repl]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.services.nrepl.nrepl-service :refer :all]
            [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(deftest test-nrepl-config
  (letfn [(process-config-fn [enabled]
            (->> {:nrepl {:enabled enabled}}
                 (partial get-in)
                 process-config
                 :enabled?))]
    (testing "Should support string value for `enabled?`"
      (is (= true (process-config-fn "true")))
      (is (= false (process-config-fn "false"))))
    (testing "Should support boolean value for `enabled?`"
      (is (= true (process-config-fn true)))
      (is (= false (process-config-fn false))))))

(deftest test-nrepl-service
  (testing "An nREPL service has been started"
    (with-app-with-config app
      [nrepl-service]
      {:nrepl {:port    7888
               :host    "0.0.0.0"
               :enabled "true"}}
      (is (= [2] (with-open [conn (repl/connect :port 7888)]
                   (-> (repl/client conn 1000)
                       (repl/message {:op "eval" :code "(+ 1 1)"})
                       (repl/response-values))))))))

(deftest test-nrepl-service
  (testing "An nREPL service without middlewares has been started"
    (with-app-with-config app
      [nrepl-service]
      {:nrepl {:port        7888
               :host        "0.0.0.0"
               :enabled     "true"
               :middlewares []}}
      (is (= [2] (with-open [conn (repl/connect :port 7888)]
                   (-> (repl/client conn 1000)
                       (repl/message {:op "eval" :code "(+ 1 1)"})
                       (repl/response-values))))))))

(deftest test-nrepl-service
  (testing "An nREPL service with test middleware has been started"
    (with-app-with-config app
      [nrepl-service]
      {:nrepl {:port        7888
               :host        "0.0.0.0"
               :enabled     "true"
               :middlewares "[puppetlabs.trapperkeeper.services.nrepl.nrepl-test-send-middleware/send-test]"}}
      (is (= "success" (with-open [conn (repl/connect :port 7888)]
                         (:test (first (-> (repl/client conn 1000)
                                           (repl/message {:op "middlewaretest"}))))))))
    (with-app-with-config app
      [nrepl-service]
      {:nrepl {:port        7888
               :host        "0.0.0.0"
               :enabled     "true"
               :middlewares ["puppetlabs.trapperkeeper.services.nrepl.nrepl-test-send-middleware/send-test"]}}
      (is (= "success" (with-open [conn (repl/connect :port 7888)]
                         (:test (first (-> (repl/client conn 1000)
                                           (repl/message {:op "middlewaretest"}))))))))))
