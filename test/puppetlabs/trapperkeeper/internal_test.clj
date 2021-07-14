(ns puppetlabs.trapperkeeper.internal-test
  (:require [clojure.test :refer :all]
            [plumbing.core :refer [fnk]]
            [plumbing.graph :as graph]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.internal :as internal]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as logging]
            [schema.core :as schema]))

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

        (is (logged? (format "Ignoring new SIGHUP restart requests; too many requests queued (%s)"
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

(def dummy-service-map-val 
  (fnk dummy-service-map-val
       :- schema/Any
       []
       (assert nil)))

;; related: https://github.com/puppetlabs/trapperkeeper/issues/294
(deftest compile-graph-test
  (testing "specialized compilation"
    (let [dummy-small-service-graph {:Service1 dummy-service-map-val}]
      (is (ifn? (internal/compile-graph dummy-small-service-graph)))))
  (testing "interpreted compilation"
    (let [dummy-huge-service-graph (into {}
                                         (map (fn [i]
                                                {(keyword (str "Service" i))
                                                 dummy-service-map-val}))
                                         ;; should be larger than the number of fields
                                         ;; allowed in a defrecord in practice.
                                         ;; related: https://github.com/plumatic/plumbing/issues/138
                                         (range 1000))]
      (is (ifn? (internal/compile-graph dummy-huge-service-graph)))))
  (testing "internal logic"
    (let [eager-compile-succeed (fn [g]
                                  ::eager-compile-succeed)
          eager-compile-too-large (fn [g]
                                    ;; simulate a "Method too large!"
                                    ;; or "Too many arguments in method signature in class file" exception
                                    ;; (ie., the symptoms of hitting the limits of graph/eager-compile).
                                    (throw (clojure.lang.Compiler$CompilerException.
                                             ""
                                             0
                                             0
                                             (Exception.))))
          interpreted-eager-compile-succeed (fn [g]
                                              ::interpreted-eager-compile-succeed)
          interpreted-eager-compile-fail (fn [g]
                                           (throw (Exception. "Interpreted compile failed")))]
      (testing "specialization succeeds"
        (is (= ::eager-compile-succeed
               (internal/compile-graph*
                 {}
                 eager-compile-succeed
                 interpreted-eager-compile-fail))))
      (testing "specialization fails, interpretation succeeds"
        (is (= ::interpreted-eager-compile-succeed
               (internal/compile-graph*
                 {}
                 eager-compile-too-large
                 interpreted-eager-compile-succeed))))
      (testing "specialization and interpretation fails, throws non-prismatic error"
        (is (thrown-with-msg?
              Exception
              #"Interpreted compile failed"
              (internal/compile-graph*
                {}
                eager-compile-too-large
                interpreted-eager-compile-fail)))))))
