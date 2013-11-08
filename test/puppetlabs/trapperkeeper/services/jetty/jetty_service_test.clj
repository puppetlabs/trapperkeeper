(ns puppetlabs.trapperkeeper.services.jetty.jetty-service-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http-client]
            [puppetlabs.trapperkeeper.services.config.config-service :refer [config-service]]
            [puppetlabs.trapperkeeper.core :refer [defservice bootstrap* parse-cli-args! get-service-fn]]
            [puppetlabs.trapperkeeper.services.jetty.jetty-service :refer :all]))

(deftest test-jetty-service
  (testing "An example TK app with a jetty service"
    (let
        [services         [(jetty-service) (config-service)]
         cli-data         (parse-cli-args! ["--config" "./test-resources/config/jetty.ini"])
         tk-app           (bootstrap* services cli-data)
         add-ring-handler (get-service-fn tk-app :jetty-service :add-ring-handler)
         join             (get-service-fn tk-app :jetty-service :join)
         body             "Hello World"
         path             "/hello_world"
         ring-handler     (fn [req] {:status 200 :body body})]
      (add-ring-handler ring-handler path)
      ;; Spin up jetty in a separate thread
      (future (join))
      ;; host and port are defined in config file used above
      (let [response (http-client/get (format "http://localhost:8080/%s/" path))]
        (is (= (response :status) 200))
        (is (= (response :body) body))))))

