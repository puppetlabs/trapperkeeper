(ns puppetlabs.trapperkeeper.common
  (:require [schema.core :as schema])
  (:import (java.util List Map Set)
          (org.yaml.snakeyaml Yaml)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def CLIData {(schema/optional-key :debug)            schema/Bool
              (schema/optional-key :bootstrap-config) schema/Str
              (schema/optional-key :config)           schema/Str
              (schema/optional-key :plugins)          schema/Str
              (schema/optional-key :restart-file)     schema/Str
              (schema/optional-key :help)             schema/Bool})

(defprotocol JavaMap->ClojureMap
  (java->clj [o]))

(extend-protocol JavaMap->ClojureMap
  Map
  (java->clj [o] (let [entries (.entrySet o)]
                   (reduce (fn [m [^String k v]]
                             (assoc m (keyword k) (java->clj v)))
                           {} entries)))

  List
  (java->clj [o] (vec (map java->clj o)))

  Set
  (java->clj [o] (set (map java->clj o)))

  Object
  (java->clj [o] o)

  nil
  (java->clj [_] nil))

(defn parse-yaml
  [yaml-string]
  ;; default in snakeyaml 2.0 is to not allow
  ;; global tags, which is the source of exploits.
  (let [yaml (new Yaml)
        data (.load yaml ^String yaml-string)]
    (java->clj data)))