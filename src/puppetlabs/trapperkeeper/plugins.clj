(ns puppetlabs.trapperkeeper.plugins
  (:import (java.util.jar JarFile)
           (java.io File))
  (:require [clojure.java.io :refer [file]]
            [clojure.tools.logging :as log]
            [puppetlabs.kitchensink.classpath :as kitchensink]
            [puppetlabs.i18n.core :as i18n]))

(defn- should-process?
  "Helper for `process-file`.  Answers whether or not the duplicate detection
  code should process a file with the given name."
  [name]
  (and
    ;; ignore directories
   (not (or (.isDirectory (file name))
            (.endsWith name "/"))) ; necessary for directories in .jars

    ;; don't care about anything in META-INF
   (not (.startsWith name "META-INF"))

    ;; lein includes project.clj ... no thank you
   (not (= name "project.clj"))))

(defn- handle-duplicate!
  "Helper for `process-file`; handles a found duplicate.  Throws an exception
  if the duplicate is a .class or .clj file.  Otherwise, logs a warning and
  returns the accumulator."
  [container-filename acc filename]
  (let [error-msg (i18n/trs "Class or namespace {0} found in both {1} and {2}"
                            filename container-filename (acc filename))]
    (if (or (.endsWith filename ".class") (.endsWith filename ".clj"))
      (throw (IllegalArgumentException. error-msg))

      ;; It is common to have other conflicts (besides classes and clojure
      ;; namespaces), especially during development (for example,
      ;; jetty-servlet and jetty-http both contain an `about.html` -
      ;; these conflicts don't exist in the uberjar anyway,
      ;; and likely aren't important.
      (log/warn error-msg)))
  acc)

(defn- process-file
  "Helper for `process-container`.  Processes a file and adds it to the
  accumulator if it is a .class or .clj file we care about."
  [container-filename acc filename]
  (if (should-process? filename)
    (if (contains? acc filename)
      (handle-duplicate! container-filename acc filename)
      (assoc acc filename container-filename))
    acc))

(defn- process-container
  "Helper for `verify-no-duplicate-resources`.
  Processes a .jar file or directory that contains classes and/or .clj sources
  and builds up map of .class/.clj filenames -> container names."
  [acc container-filename]
  (let [file (file container-filename)]
    (if (.exists file)
      (let [filenames (if (.isDirectory file)
                        (map #(.getPath %) (file-seq file))
                        (map #(.getName %) (enumeration-seq (.entries (JarFile. file)))))]
        (reduce (partial process-file container-filename) acc filenames))
      acc ; There may be directories on the classpath that do not exist.
)))

(defn jars-in-dir
  "Given a path to a directory on disk, returns a collection of all of the .jar
  files contained in that directory (not recursive)."
  [dir]
  {:pre [(instance? File dir)]
   :post [(coll? %)
          (every? (partial instance? File) %)]}
  (filter #(.endsWith (.getAbsolutePath %) ".jar") (.listFiles dir)))

(defn verify-no-duplicate-resources
  "Examines all resources on the classpath and contained in the given directory
  and checks for duplicates.  A resource in this context is defined as a .class
  or .clj file.  Throws an Exception if any duplicates are found."
  [dir]
  {:pre [(instance? File dir)]}
  (let [plugin-jars           (jars-in-dir dir)
        classpath             (System/getProperty "java.class.path")
        ;; When running as an uberjar, this system property contains only
        ;; the path to the uberjar (-classpath is ignored).
        classpath-containers  (if (.contains classpath ":")
                                (.split classpath ":")
                                [classpath])
        all-containers        (concat plugin-jars classpath-containers)]
    (reduce process-container {} all-containers)))

(defn add-plugin-jars-to-classpath!
  "Add all of .jar files contained in the plugins directory
  (specified by the '--plugins' CLI argument) to the classpath."
  [plugins-path]
  (when plugins-path
    (let [plugins (file plugins-path)]
      (if (.exists plugins)
        (do
          (verify-no-duplicate-resources plugins)
          (doseq [jar (jars-in-dir plugins)]
            (log/info (i18n/trs "Adding plugin {0} to classpath." (.getAbsolutePath jar)))
            (kitchensink/add-classpath jar)
            (kitchensink/add-classpath jar (clojure.lang.RT/baseLoader))))
        (throw (IllegalArgumentException.
                 (i18n/trs "Plugins directory {0} does not exist" plugins-path)))))))
