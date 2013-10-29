(ns puppetlabs.trapperkeeper.core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :refer :all]
            [puppetlabs.utils :refer [boolean?]]))

(deftest testing-utils-dep
  (testing "Just testing that we can resolve our dependency on puppetlabs/clj-utils"
    (is (boolean? true))))
