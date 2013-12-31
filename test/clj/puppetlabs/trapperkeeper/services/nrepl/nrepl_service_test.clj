(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service-test
  (:require [clojure.test :refer :all]
            [clojure.tools.nrepl :as repl]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-cli-data]]
            [puppetlabs.trapperkeeper.app :refer [get-service-fn]]
            [puppetlabs.trapperkeeper.services.nrepl.nrepl-service :refer :all]))

(deftest test-nrepl-service
  (testing "An nREPL service has been started"
    (let [app      (bootstrap-services-with-cli-data [(nrepl-service)] {:config "./test-resources/config/nrepl/nrepl.ini"})
          shutdown (get-service-fn app :nrepl-service :shutdown)]
      (try
        (is (= [2] (with-open [conn (repl/connect :port 7888)]
                     (-> (repl/client conn 1000)
                         (repl/message {:op "eval" :code "(+ 1 1)"})
                         (repl/response-values)))))
      (finally
        (shutdown))))))
