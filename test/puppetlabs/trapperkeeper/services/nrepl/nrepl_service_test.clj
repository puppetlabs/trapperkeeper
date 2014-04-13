(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service-test
  (:require [clojure.test :refer :all]
            [clojure.tools.nrepl :as repl]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.services.nrepl.nrepl-service :refer :all]))

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
