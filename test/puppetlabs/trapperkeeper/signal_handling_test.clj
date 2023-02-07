(ns puppetlabs.trapperkeeper.signal-handling-test
  (:require
   [puppetlabs.trapperkeeper.core :as core]))

(defn- start-test [context get-in-config]
  (let [continue? (atom true)
        thread (future
                 (try ;; future just discards top-level exceptions
                   (while @continue?
                     (let [target (get-in-config [:signal-test-target])]
                       (assert target)
                       (Thread/sleep 200)
                       (spit target "exciting")))
                   (catch Throwable ex
                     (prn ex)
                     (throw ex))))]
    (assoc context
           :finish-signal-test
           (fn exit-signal-test []
             (reset! continue? false)
             @thread))))

(defn- stop-test [{:keys [finish-signal-test] :as context}]
  (finish-signal-test)
  context)

(defprotocol SignalHandlingTestService)

(core/defservice signal-handling-test-service
  SignalHandlingTestService
  [[:ConfigService get-in-config]]
  (init [this context] context)
  (start [this context] (start-test context get-in-config))
  (stop [this context] (stop-test context)))
