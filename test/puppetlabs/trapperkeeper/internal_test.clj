(ns puppetlabs.trapperkeeper.internal-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.internal :as internal]))

(deftest test-queued-restarts
  (testing "main lifecycle and calls to `restart-tk-apps` are not executed concurrently"
    (let [boot-promise (promise)
          lifecycle-events (atom [])
          svc (tk/service
               []
               (init [this context]
                     (swap! lifecycle-events conj :init)
                     context)
               (start [this context]
                      @boot-promise
                      (swap! lifecycle-events conj :start)
                      context)
               (stop [this context]
                     (swap! lifecycle-events conj :stop)
                     context))
          config-fn (constantly {})
          app (internal/build-app* [svc] config-fn)
          main-thread (future (internal/boot-services-for-app* app))]
      (while (< (count @lifecycle-events) 1)
        (Thread/yield))
      (is (= [:init] @lifecycle-events))
      (is (not (realized? main-thread)))
      (let [restart1-scheduled (promise)
            restart1-thread (future (do (internal/restart-tk-apps [app])
                                        (deliver restart1-scheduled true)))
            restart2-scheduled (promise)
            restart2-thread (future (do (internal/restart-tk-apps [app])
                                        (deliver restart2-scheduled true)))]
        @restart1-scheduled
        (is (= [:init] @lifecycle-events))
        @restart1-thread
        @restart2-scheduled
        (is (= [:init] @lifecycle-events))
        @restart2-thread)

      (deliver boot-promise true)
      @main-thread
      (while (< (count @lifecycle-events) 8)
        (Thread/yield))
      (is (= [:init :start :stop :init :start :stop :init :start]
             @lifecycle-events))
      (tk-app/stop app)
      (is (= [:init :start :stop :init :start :stop :init :start :stop]
             @lifecycle-events)))))