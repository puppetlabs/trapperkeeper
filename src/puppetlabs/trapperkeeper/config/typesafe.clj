(ns puppetlabs.trapperkeeper.config.typesafe
  (:import (java.util Map List)
           (com.typesafe.config ConfigFactory))
  (:require [clojure.java.io :as io]
            [puppetlabs.kitchensink.core :as ks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(declare java-config->clj)

(defn strip-quotes
  "Given a string read from a config file, check to see if it begins and ends with
  double-quotes, and if so, remove them."
  [s]
  {:pre [(string? s)]
   :post [(string? %)
          (not (and (.startsWith % "\"")
                    (.endsWith % "\"")))]}
  (if (and (.startsWith s "\"")
           (.endsWith s "\""))
    (.substring s 1 (dec (.length s)))
    s))

(defn string->val
  "Given a string read from a config file, convert it to the corresponding value
  that we will use for our internal configuration data.  This includes removing
  surrounding double-quotes and casting to an integer when possible."
  [s]
  {:pre [(string? s)]
   :post [((some-fn string? integer?) %)]}
  (let [v (strip-quotes s)]
    (or (ks/parse-int v) v)))

(defn nested-java-map->map
  "Given a (potentially nested) java Map object read from a config file,
  convert it (potentially recursively) to a clojure map with keywordized keys."
  [m]
  {:pre [(instance? Map m)]
   :post [(map? %)
          (every? keyword? (keys %))]}
  (reduce
    (fn [acc [k v]]
      (assoc acc (keyword k)
                 (java-config->clj v)))
    {}
    (.entrySet m)))

(defn java-list->vec
  "Given a java List object read from a config file, convert it to a clojure
  vector for use in our internal configuration representation."
  [l]
  {:pre [(instance? List l)]
   :post [(vector? %)]}
  (mapv java-config->clj l))

(defn java-config->clj
  "Given a java configuration object read from a config file, convert it to a
  clojure object suitable for use in our internal configuration representation."
  [v]
  (cond
    (instance? Map v)   (nested-java-map->map v)
    (instance? List v)  (java-list->vec v)
    (string? v)         (string->val v)
    ;; Any other types we need to treat specially here?
    :else v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(defn config-file->map
  "Given the path to a configuration file (of type .conf, .json, or .properties),
  parse the file and return a map suitable for use in our internal configuration
  representation."
  [file-path]
  {:pre [(string? file-path)]
   :post [(map? %)]}
  (-> (io/file file-path)
      (ConfigFactory/parseFileAnySyntax)
      (.root)
      (.unwrapped)
      (nested-java-map->map)))

