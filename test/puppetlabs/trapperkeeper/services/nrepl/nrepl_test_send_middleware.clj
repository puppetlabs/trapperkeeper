(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-test-send-middleware
  (:require [clojure.tools.nrepl.transport :as t]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]])
  (:use [clojure.tools.nrepl.misc :only [response-for]]))

(defn send-test
  [h]
  (fn [{:keys [op transport] :as msg}]
    (if (= "middlewaretest" op)
      (t/send transport (response-for msg :status "done" :test "success"))
      (h msg))))

(set-descriptor!
 #'send-test
 {:requires #{}
  :expects #{}})
