(ns puppetlabs.trapperkeeper.services.jetty.jetty-core-test
  (:require [clojure.test :refer :all]
            [ring.util.response :as rr]
            [clj-http.client :as http-client]
            [puppetlabs.trapperkeeper.services.jetty.jetty-core :as jetty]
            [puppetlabs.trapperkeeper.testutils.jetty :refer [with-test-jetty]]))

(deftest compression
  (testing "should return"
    (let [body (apply str (repeat 1000 "f"))
          app  (fn [req]
                 (-> body
                     (rr/response)
                     (rr/status 200)
                     (rr/content-type "text/plain")
                     (rr/charset "UTF-8")))]
      (with-test-jetty app port
        (testing "a gzipped response when requests"
          ;; The client/get function asks for compression by default
          (let [resp (http-client/get (format "http://localhost:%d/" port))]
            (is (= (resp :body) body))
            (is (= (get-in resp [:headers "content-encoding"]) "gzip")
                (format "Expected gzipped response, got this response: %s" resp))))

        (testing "an uncompressed response by default"
          ;; The client/get function asks for compression by default
          (let [resp (http-client/get (format "http://localhost:%d/" port) {:decompress-body false})]
            (is (= (resp :body) body))
            ;; We should not receive a content-encoding header in the uncompressed case
            (is (nil? (get-in resp [:headers "content-encoding"]))
                (format "Expected uncompressed response, got this response: %s" resp))))))))
