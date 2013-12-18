(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-service-test
  (:require [clojure.test :refer :all]
            [clojure.tools.nrepl :as repl]
            [puppetlabs.trapperkeeper.core :refer [bootstrap*]]
            [puppetlabs.trapperkeeper.services :refer [get-service-fn]]
            [puppetlabs.trapperkeeper.services.nrepl.nrepl-service :refer :all]))

(deftest test-nrepl-service
  (testing "An nREPL service has been started"
    (let [services  [(nrepl-service)]
          cli-data  {:config "./test-resources/config/nrepl/nrepl.ini"}
          tk-app    (bootstrap* services cli-data)
          shutdown  (get-service-fn tk-app :nrepl-service :shutdown)]
      (try
        (is (= [2] (with-open [conn (repl/connect :port 7888)]
                  (-> (repl/client conn 1000)
                      (repl/message {:op "eval" :code "(+ 1 1)"})
                      repl/response-values))))
      (finally
        (shutdown))))))







