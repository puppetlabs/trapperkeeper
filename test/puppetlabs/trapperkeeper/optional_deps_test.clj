(ns puppetlabs.trapperkeeper.optional-deps-test
  (:require  [clojure.test :refer :all]
             [puppetlabs.trapperkeeper.services :refer [service defservice get-service] :as tks]
             [puppetlabs.trapperkeeper.app :as tka]
             [puppetlabs.trapperkeeper.core :refer [build-app] :as tkc]
             [schema.test :as schema-test]))

(use-fixtures :once schema-test/validate-schemas)

(defprotocol HaikuService
  (haiku [this topic]))

(defprotocol SonnetService
  (sonnet [this topic couplet]))

(defprotocol PoetryService
  (get-haiku [this])
  (get-sonnet [this]))

(defservice haiku-service
  HaikuService
  []
  (haiku [this topic] ["here is a haiku"
                       "about the topic you want"
                       topic]))

(defservice sonnet-service
  SonnetService {:required []
                 :optional []}
  (sonnet [this topic couplet] (vec (concat ["imagine a sonnet"
                                             (format "about %s" topic)]
                                            couplet))))

(deftest optional-deps-test
  (testing "when using defservice"
    (testing "with destructuring"
      (testing "and protocol"
        (defservice poetry-svc-w-destructure-and-proto
          PoetryService
          {:required [[:HaikuService haiku]]
           :optional [SonnetService]}
          (init [this ctx] ctx)
          (get-haiku [this] (haiku "meh"))
          (get-sonnet [this] "feh"))
        (is (build-app [poetry-svc-w-destructure-and-proto haiku-service] {})))
      (testing "and no protocol"
        (defservice poetry-svc-w-destructure
          {:required [[:HaikuService haiku]]
           :optional [SonnetService]}
          (init [this ctx] ctx))
        (is (build-app [poetry-svc-w-destructure haiku-service] {}))))

    (testing "with a protocol"
      (defservice poetry-svc-w-protocol
        PoetryService
        {:required [HaikuService]
         :optional [SonnetService]}
        (init [this ctx] ctx)
        (get-haiku [this] "haiku")
        (get-sonnet [this] "sonnet"))
      (is (build-app [poetry-svc-w-protocol haiku-service] {})))

    (testing "without a protocol"
      (defservice poetry-svc-wo-protocol
        {:required [HaikuService]
         :optional [SonnetService]}
        (init [this ctx] ctx))
      (is (build-app [poetry-svc-wo-protocol haiku-service] {})))

    (testing "with metadata"
      (testing "and a protocol"
        (defservice poetry-svc-w-meta-and-proto
          {:private true}
          PoetryService
          {:required [HaikuService]
           :optional [SonnetService]}
          (init [this ctx] ctx)
          (get-haiku [this] "haiku")
          (get-sonnet [this] "sonnet"))
        (is (build-app [poetry-svc-w-meta-and-proto haiku-service] {})))
      (testing "and no protocol"
        (defservice poetry-svc-w-meta-and-no-proto
          {:private true}
          {:required [HaikuService]
           :optional [SonnetService]}
          (init [this ctx] ctx))
        (is (build-app [poetry-svc-w-meta-and-no-proto haiku-service] {}))))

    (testing "with a docstring"
      (testing "and a protocol"
        (defservice poetry-svc-w-doc-and-proto
          "foo bar butt"
          PoetryService
          {:required [HaikuService]
           :optional [SonnetService]}
          (init [this ctx] ctx)
          (get-haiku [this] "haiku")
          (get-sonnet [this] "sonnet"))
        (is (build-app [poetry-svc-w-doc-and-proto haiku-service] {})))
      (testing "and no protocol"
        (defservice poetry-svc-w-doc-and-no-proto
          "fop blop bork"
          {:required [HaikuService]
           :optional [SonnetService]}
          (init [this ctx] ctx))
        (is (build-app [poetry-svc-w-doc-and-no-proto haiku-service] {}))))

    (testing "with docstring and metadata"
      (testing "and a protocol"
        (defservice poetry-svc-w-everything
          "flarp"
          {:private true}
          PoetryService
          {:required [HaikuService]
           :optional [SonnetService]}
          (init [this ctx] ctx)
          (get-haiku [this] "haiku")
          (get-sonnet [this] "sonnet"))
        (is (build-app [poetry-svc-w-everything haiku-service] {})))
      (testing "and no protocol"
        (defservice poetry-svc-w-everything-but-proto
          "fop blop bork"
          {:required [HaikuService]
           :optional [SonnetService]}
          (init [this ctx] ctx))
        (is (build-app [poetry-svc-w-everything-but-proto haiku-service] {})))))


  (testing "when not using a protocol"
    (let [poetry-service (service {:required [HaikuService]
                                   :optional [SonnetService]}
                           (init [this ctx]
                             (assoc ctx
                                    :haiku-svc (get-service this :HaikuService)
                                    :sonnet-svc (tks/maybe-get-service this :SonnetService))))]
      (is (build-app [poetry-service haiku-service] {}))))
  (testing "when dep form is well formed"
    (testing "when there are no optional deps"
      (let [poetry-service (service PoetryService
                             {:required [HaikuService SonnetService]
                              :optional []}
                             (get-haiku [this]
                               (let [haiku-svc (get-service this :HaikuService)]
                                 (haiku haiku-svc "tea leaves thwart those who")))
                             (get-sonnet [this]
                               (let [sonnet-svc (get-service this :SonnetService)]
                                 (sonnet sonnet-svc "designing futures" ["rhyming" "is overrated"]))))
            app (build-app [haiku-service poetry-service sonnet-service] {})]
        (is (= ["here is a haiku"
                "about the topic you want"
                "tea leaves thwart those who"]
               (get-haiku (tka/get-service app :PoetryService))))
        (is (= ["imagine a sonnet"
                "about designing futures"
                "rhyming"
                "is overrated"]
               (get-sonnet (tka/get-service app :PoetryService))))))

    (testing "when there are normal optional deps"
      (testing "and they are all included"
        (let [poetry-service (service PoetryService
                               {:required []
                                :optional [HaikuService SonnetService]}
                               (get-haiku [this]
                                 (let [haiku-svc (get-service this :HaikuService)]
                                   (haiku haiku-svc "tea leaves thwart those who")))
                               (get-sonnet [this]
                                 (let [sonnet-svc (get-service this :SonnetService)]
                                   (sonnet sonnet-svc "designing futures" ["rhyming" "is overrated"]))))
              app (build-app [haiku-service poetry-service sonnet-service] {})]
          (is (= ["here is a haiku"
                  "about the topic you want"
                  "tea leaves thwart those who"]
                 (get-haiku (tka/get-service app :PoetryService))))
          (is (= ["imagine a sonnet"
                  "about designing futures"
                  "rhyming"
                  "is overrated"]
                 (get-sonnet (tka/get-service app :PoetryService))))))

      (testing "and one is excluded"
        (let [poetry-service (service PoetryService
                               {:required []
                                :optional [HaikuService SonnetService]}
                               (get-haiku [this]
                                 (let [haiku-svc (get-service this :HaikuService)]
                                   (haiku haiku-svc "tea leaves thwart those who")))
                               (get-sonnet [this]
                                 (if (tks/service-included? this :SonnetService)
                                   (sonnet (get-service this :SonnetService) "designing futures" ["rhyming" "is overrated"])
                                   ["imagine the saddest sonnet"])))
              app (build-app [haiku-service poetry-service] {})]
          (is (= ["here is a haiku"
                  "about the topic you want"
                  "tea leaves thwart those who"]
                 (get-haiku (tka/get-service app :PoetryService))))
          (is (= ["imagine the saddest sonnet"]
                 (get-sonnet (tka/get-service app :PoetryService))))))

      (testing "and all are excluded"
        (let [poetry-service (service PoetryService
                               {:required []
                                :optional [HaikuService SonnetService]}
                               (get-haiku [this]
                                 (if-let [haiku-svc (tks/maybe-get-service this :HaikuService)]
                                   (haiku haiku-svc ["tea leaves thwart those who"])
                                   ["imagine the saddest haiku"]))
                               (get-sonnet [this]
                                 (if (tks/service-included? this :SonnetService)
                                   (sonnet (get-service this :SonnetService) "designing futures" ["rhyming" "is overrated"])
                                   ["imagine the saddest sonnet"])))
              app (build-app [poetry-service] {})]
          (is (= ["imagine the saddest haiku"]
                 (get-haiku (tka/get-service app :PoetryService))))
          (is (= ["imagine the saddest sonnet"]
                 (get-sonnet (tka/get-service app :PoetryService)))))))

    (testing "when there is a destructured required dep"
      (let [poetry-service (service PoetryService
                             {:required [[:SonnetService sonnet]]
                              :optional [HaikuService]}
                             (get-haiku [this]
                               (if-let [haiku-svc (tks/maybe-get-service this :HaikuService)]
                                 (haiku haiku-svc ["tea leaves thwart those who"])
                                 ["imagine the saddest haiku"]))
                             (get-sonnet [this] (sonnet "designing futures" ["rhyming" "is overrated"])))
            app (build-app [poetry-service sonnet-service] {})]
        (is (= ["imagine the saddest haiku"]
               (get-haiku (tka/get-service app :PoetryService))))
        (is (= ["imagine a sonnet"
                "about designing futures"
                "rhyming"
                "is overrated"]
               (get-sonnet (tka/get-service app :PoetryService))))))))
