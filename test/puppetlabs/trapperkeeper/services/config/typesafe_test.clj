(ns puppetlabs.trapperkeeper.services.config.typesafe-test
  (:require [puppetlabs.trapperkeeper.config.typesafe :as ts]
            [clojure.test :refer :all]))

(deftest configfile->map-test
  (testing "can parse .properties file with nested data structures"
    (let [cfg (ts/config-file->map "./test-resources/config/file/config.properties")]
      (is (= {:foo {:bar "barbar"
                    :baz "bazbaz"
                    :bam 42
                    :bap {:boozle "boozleboozle"}}}
             cfg))))
  (testing "can parse .json file with nested data structures"
    (let [cfg (ts/config-file->map "./test-resources/config/file/config.json")]
      (is (= {:foo {:bar "barbar"
                    :baz "bazbaz"
                    :bam 42
                    :bap {:boozle "boozleboozle"
                          :bip [1 2 {:hi "there"} 3]}}}
             cfg))))
  (testing "can parse .conf file with nested data structures"
    (let [cfg (ts/config-file->map "./test-resources/config/file/config.conf")]
      (is (= {:foo {:bar "barbar"
                    :baz "bazbaz"
                    :bam 42
                    :bap {:boozle "boozleboozle"
                          :bip [1 2 {:hi "there"} 3]}}}
             cfg)))))