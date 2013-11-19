(ns puppetlabs.trapperkeeper.services.jetty.jetty-service-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as http-client]
            [puppetlabs.trapperkeeper.core :refer [defservice bootstrap* parse-cli-args! get-service-fn config-service]]
            [puppetlabs.trapperkeeper.services.jetty.jetty-service :refer :all]))

(deftest test-jetty-service
  (testing "An example TK app with a jetty service"
    (let [services         [(jetty-service)]
          cli-data         (parse-cli-args! ["--config" "./test-resources/config/jetty/jetty.ini"])
          tk-app           (bootstrap* services cli-data)
          add-ring-handler (get-service-fn tk-app :jetty-service :add-ring-handler)
          join             (get-service-fn tk-app :jetty-service :join)
          shutdown         (get-service-fn tk-app :jetty-service :shutdown)
          body             "Hello World"
          path             "/hello_world"
          ring-handler     (fn [req] {:status 200 :body body})]
      (try
        (add-ring-handler ring-handler path)
        ;; Spin up jetty in a separate thread
        (future (join))
        ;; host and port are defined in config file used above
        (let [response (http-client/get (format "http://localhost:8080/%s/" path))]
          (is (= (response :status) 200))
          (is (= (response :body) body)))
        (finally
          (shutdown)))))

  (testing "SSL initialization is supported for both .jks and .pem implementations"
    (doseq [config ["./test-resources/config/jetty/jetty-ssl-jks.ini"
                    "./test-resources/config/jetty/jetty-ssl-pem.ini"]]
      (let [app               (bootstrap* [(jetty-service)]
                                          (parse-cli-args! ["--config" config]))
            add-ring-handler  (get-service-fn app :jetty-service :add-ring-handler)
            join              (get-service-fn app :jetty-service :join)
            shutdown          (get-service-fn app :jetty-service :shutdown)
            body              "Hi World"
            path              "/hi_world"
            ring-handler      (fn [req] {:status 200 :body body})]
        (try
          (add-ring-handler ring-handler path)
          (future (join))
          ;; NOTE that we're not entirely testing SSL here since we're not hitting https 8081
          ;; but this at least tests the initialization. Unfortunately when you are using a
          ;; self-signed certificate on the server it's really hard to do a client request
          ;; against it without getting an SSL error.
          (let [response (http-client/get (format "http://localhost:8080/%s/" path))]
            (is (= (:status response) 200))
            (is (= (:body response) body)))
          (finally
            (shutdown)))))))

