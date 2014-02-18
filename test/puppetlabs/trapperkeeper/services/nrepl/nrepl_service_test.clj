(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service-test
  (:require [clojure.test :refer :all]
            [clojure.tools.nrepl :as repl]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-cli-data]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-def-id stop service-context]]
            [puppetlabs.trapperkeeper.services.nrepl.nrepl-service :refer :all]))

(deftest test-nrepl-service
  (testing "An nREPL service has been started"
    (let [app      (bootstrap-services-with-cli-data [nrepl-service] {:config "./test-resources/config/nrepl/nrepl.ini"})
          si       (get-service app (service-def-id nrepl-service))]
      (try
        (is (= [2] (with-open [conn (repl/connect :port 7888)]
                     (-> (repl/client conn 1000)
                         (repl/message {:op "eval" :code "(+ 1 1)"})
                         (repl/response-values)))))
      (finally
        (stop si (service-context si)))))))
