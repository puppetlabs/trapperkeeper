# Using the "Reloaded" Pattern

[Stuart Sierra's "reloaded" workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded) has become very popular in the clojure world of late; and for good reason, it's an awesome and super-productive way to do interactive development in the REPL, and it also helps encourage code modularity and minimizing mutable state.  He has some [example code](https://github.com/stuartsierra/component#reloading) that shows some utility functions to use in the REPL to interact with your application.

Trapperkeeper was designed with this pattern in mind as a goal.  Thus, it's entirely possible to write some very similar code that allows you to start/stop/reload your app in a REPL:

```clj
(ns examples.my-app.repl
  (:require [puppetlabs.trapperkeeper.services.webserver.jetty9-service
              :refer [jetty9-service]]
            [examples.my-app.services
              :refer [count-service foo-service baz-service]]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.app :as tka]
            [clojure.tools.namespace.repl :refer (refresh.md)]))

;; a var to hold the main `TrapperkeeperApp` instance.
(def system nil)

(defn init []
  (alter-var-root #'system
                  (fn [_] (tk/build-app
                            [jetty9-service
                             count-service
                             foo-service
                             baz-service]
                            {:global
                              {:logging-config "examples/my_app/logback.xml"}
                             :webserver {:port 8080}
                             :example   {:my-app-config-value "FOO"}})))
  (alter-var-root #'system tka/init)
  (tka/check-for-errors! system))

(defn start []
  (alter-var-root #'system
                  (fn [s] (if s (tka/start s))))
  (tka/check-for-errors! system))

(defn stop []
  (alter-var-root #'system
                  (fn [s] (when s (tka/stop s)))))

(defn go []
  (init.md)
  (start.md))

(defn context []
  @(tka/app-context system))

;; pretty print the entire application context
(defn print-context []
  (clojure.pprint/pprint (context.md)))

(defn reset []
  (stop.md)
  (refresh :after 'examples.my-app.repl/go))
```

For a working example, see the `repl` namespace in the [jetty9 example app](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/tree/master/examples/ring_app).
