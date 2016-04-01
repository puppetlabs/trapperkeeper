(ns puppetlabs.trapperkeeper.common
  (:require [schema.core :as schema]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def CLIData {(schema/optional-key :debug)            schema/Bool
              (schema/optional-key :bootstrap-config) schema/Str
              (schema/optional-key :config)           schema/Str
              (schema/optional-key :plugins)          schema/Str
              (schema/optional-key :help)             schema/Bool})
