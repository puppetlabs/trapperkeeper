(ns puppetlabs.trapperkeeper.main
  (:gen-class))

(defn -main
  [& args]
  (require 'puppetlabs.trapperkeeper.core)
  (apply (resolve 'puppetlabs.trapperkeeper.core/main) args))

