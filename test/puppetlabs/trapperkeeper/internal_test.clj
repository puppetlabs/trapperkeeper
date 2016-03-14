(ns puppetlabs.trapperkeeper.internal-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [clojure.tools.logging :as log]))

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

(deftest test-max-queued-restarts
  (let [stop-promise (promise)
        lifecycle-events (atom [])
        svc (tk/service
             []
             (init [this context]
                   (swap! lifecycle-events conj :init)
                   context)
             (start [this context]
                    (swap! lifecycle-events conj :start)
                    context)
             (stop [this context]
                   @stop-promise
                   (swap! lifecycle-events conj :stop)
                   context))
        app (testutils/bootstrap-services-with-config
             [svc]
             {})]

    ;; the first restart will be picked up by the async worker, but it will
    ;; block on the 'stop-promise', so no more work can be picked up off of the
    ;; queue
    (internal/restart-tk-apps [app])

    ;; now we issue how ever many restarts we need to to fill up the queue
    (dotimes [i internal/max-pending-lifecycle-events]
      (internal/restart-tk-apps [app]))

    ;; now we choose some arbitrary number of additional restarts to request,
    ;; and confirm that we get a log message indicating that they were rejected
    (dotimes [i 3]
      (logging/with-test-logging
       (internal/restart-tk-apps [app])

       (is (logged? (format "Too many SIGHUP restart requests queued (%d); ignoring!"
                            internal/max-pending-lifecycle-events)
                    :warn)
           "Missing expected log message when too many HUP requests queued")))

    ;; now we unblock all of the queued restarts
    (deliver stop-promise true)

    ;; and validate that the life cycle events match up to that number of restarts
    (let [expected-lifecycle-events (->> [:stop :init :start] ; each restart will add these
                                         (repeat (+ 1 internal/max-pending-lifecycle-events))
                                         (apply concat)
                                         (concat [:init :start]) ; here is the initial init/start
                                         vec)]
      (while (< (count @lifecycle-events) (count expected-lifecycle-events))
        (Thread/yield))
      (is (= expected-lifecycle-events @lifecycle-events))

      ;; now we stop the app
      (tk-app/stop app)
      ;; and make sure that we got one last :stop
      (is (= (conj expected-lifecycle-events :stop)
             @lifecycle-events)))))