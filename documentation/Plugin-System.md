# Experimental Plugin System

Trapperkeeper has an **extremely** simple, experimental plugin mechanism.  It allows you to specify (as a command-line argument) a directory of "plugin" .jars that will be dynamically added to the classpath at runtime.  Each jar file will also be checked for duplicate classes or namespaces before it is added, so as to prevent any unexpected behavior.

This provides the ability to extend the functionality of a deployed, Trapperkeeper-based application by simply including one or more services packaged into standalone "plugin" jar files, and adding the additional service(s.md) to the bootstrap configuration.

Projects that wish to package themselves as "plugin" jar files should build an uberjar containing all of their dependencies.  However, there is one caveat here - Trapperkeeper *and all of its dependencies* should be excluded from the uberjar.  If the exclusions are not defined correctly, Trapperkeeper will fail to start because there will be duplicate versions of classes/namespaces on the classpath.

Plugins are specified via a command-line argument: `--plugins /path/to/plugins/directory`; every .jar file in that directory will be added to the classpath by Trapperkeeper.
