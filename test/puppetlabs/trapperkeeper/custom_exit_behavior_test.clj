(ns puppetlabs.trapperkeeper.custom-exit-behavior-test
  (:require
   [puppetlabs.trapperkeeper.core :as core]))

(defprotocol CustomExitBehaviorTestService)

(core/defservice custom-exit-behavior-test-service
  CustomExitBehaviorTestService
  [[:ShutdownService request-shutdown]]
  (init [this context] context)
  (start [this context]
         (request-shutdown {::core/exit {:messages [["Some excitement!\n" *out*]
                                                    ["More excitement!\n" *err*]]
                                         :status 7}})
         context)
  (stop [this context] context))
