(ns puppetlabs.trapperkeeper.services.jetty.jetty-service-test
  (:import  [servlet SimpleServlet])
  (:require [clojure.test :refer :all]
            [clj-http.client :as http-client]
            [puppetlabs.trapperkeeper.app :refer [get-service-fn]]
            [puppetlabs.trapperkeeper.services.jetty.jetty-service :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [bootstrap-services-with-empty-config
                                                                  bootstrap-services-with-cli-data]]
            [puppetlabs.kitchensink.testutils.fixtures :refer [with-no-jvm-shutdown-hooks]]))

(use-fixtures :once with-no-jvm-shutdown-hooks)

(deftest jetty-webserver-service
  (testing "ring support"
    (let [app              (bootstrap-services-with-cli-data [(webserver-service)] {:config "./test-resources/config/jetty/jetty.ini"})
          add-ring-handler (get-service-fn app :webserver-service :add-ring-handler)
          join             (get-service-fn app :webserver-service :join)
          shutdown         (get-service-fn app :webserver-service :shutdown)
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

  (testing "servlet support"
    (let [app                 (bootstrap-services-with-empty-config [(webserver-service)])
          add-servlet-handler (get-service-fn app :webserver-service :add-servlet-handler)
          join                (get-service-fn app :webserver-service :join)
          shutdown            (get-service-fn app :webserver-service :shutdown)
          body                "Hey there"
          path                "/hey"
          servlet             (SimpleServlet. body)]
      (try
        (add-servlet-handler servlet path)
        (future (join))
        (let [response (http-client/get (format "http://localhost:8080/%s" path))]
          (is (= (:status response) 200))
          (is (= (:body response) body)))
        (finally
          (shutdown)))))

  (testing "SSL initialization is supported for both .jks and .pem implementations"
    (doseq [config ["./test-resources/config/jetty/jetty-ssl-jks.ini"
                    "./test-resources/config/jetty/jetty-ssl-pem.ini"]]
      (let [app               (bootstrap-services-with-cli-data [(webserver-service)] {:config config})
            add-ring-handler  (get-service-fn app :webserver-service :add-ring-handler)
            join              (get-service-fn app :webserver-service :join)
            shutdown          (get-service-fn app :webserver-service :shutdown)
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
