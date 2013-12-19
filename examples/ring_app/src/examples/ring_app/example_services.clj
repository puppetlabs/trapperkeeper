(ns examples.ring-app.example-services
  (:require [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.core :refer [pprint-to-string]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]))

(def ^{:private true} hit-count (atom {}))

(defn- inc-and-get
  "Increments the hit count for the provided endpoint and returns the new hit count."
  [endpoint]
  {:pre [(string? endpoint)]
   :post [(integer? %) (> % 0)]}

  (let [new-hit-counts (swap! hit-count #(assoc % endpoint (cond (contains? % endpoint)
                                                             (inc (% endpoint)) :else 1)))]

    (log/debug "Incrementing hit count for" endpoint "from"
               (dec (new-hit-counts endpoint)) "to" (new-hit-counts endpoint))

    (new-hit-counts endpoint)))

(defservice count-service
  "This is a simple service which simply keeps a counter. It contains one function, inc-and-get, which
   increments the count and returns it."

  ;; This map declares the service's dependencies on other services and their functions,
  {:depends []              ; A :depends key is required to be present, even if it is empty.
   :provides [inc-and-get]} ; This service provides a function called inc-and-get.

  ;; Export the inc-and-get function via the return map.
  {:inc-and-get inc-and-get})

(defn- success-response
  "Return a ring response map containing a HTTP response code of 200 (OK) and HTML which displays the hitcount on this
   endpoint as well as all the data provided by Ring."
  [hit-count req]
  {:status 200
   :body (str "<h1>Hello from http://" (:server-name req) ":" (:server-port req) (:uri req) "</h1>"
              (if (:debug? req) "<h3>DEBUGGING ENABLED!</h3>" "")
              "<p>You are visitor number " hit-count ".</p>"
              "<pre>" (pprint-to-string req) "</pre>")})

(defn- ring-handler
  "Executes the inc-and-get command and passes it into success-reponse which generates a ring response."
  [inc-and-get endpoint req]
  (success-response (inc-and-get endpoint) req))

(defservice bert-service
  "This is the bert web service. The Clojure web application library, Ring, is used to create simple
   responses to an endpoint. It depends on the count-service above to use as a primitive hit counter.
   See https://github.com/ring-clojure/ring for documentation on Ring."

  ;; This service needs functionality from the webserver-service, and the count service.
  {:depends [[:webserver-service add-ring-handler]
             [:count-service inc-and-get]]
   ;; This service provides a shutdown function.
   :provides [shutdown]}

  (let [endpoint "/bert"]
    (add-ring-handler (partial ring-handler inc-and-get endpoint) endpoint)
    ;; Return the service's exposed function map.
    {:shutdown #(log/info "Bert service shutting down")}))

(defn debug-middleware
  "Ring middleware to add the :debug configuration value to the request map."
  [app debug?]
  (fn [req]
    (app (assoc req :debug? debug?))))

(defservice ernie-service
  "This is the ernie service which operates on the /ernie endpoint. It is essentially identical to the bert service."

  {:depends [[:webserver-service add-ring-handler]
             [:count-service inc-and-get]
             [:config-service get-in-config]]
   :provides [shutdown]}

  (let [endpoint      (get-in-config [:example :ernie-url-prefix])
        ring-handler  (-> (partial ring-handler inc-and-get endpoint)
                          (debug-middleware (get-in-config [:debug])))]
    (add-ring-handler ring-handler endpoint)
    {:shutdown #(log/info "Ernie service shutting down") }))
