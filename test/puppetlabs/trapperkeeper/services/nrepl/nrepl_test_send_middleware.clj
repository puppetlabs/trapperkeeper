(ns puppetlabs.trapperkeeper.services.nrepl.nrepl-test-send-middleware
  (:require [nrepl.transport :as t]
            [nrepl.middleware :refer [set-descriptor!]])
  (:use [nrepl.misc :only [response-for]]))

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
