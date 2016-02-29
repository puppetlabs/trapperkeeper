# Trapperkeeper's Built-in Configuration Service

The configuration service is built-in to Trapperkeeper and is always loaded.  It performs the following tasks at application startup:

* Reads all application configuration into memory
* Initializes logging
* Provides functions that can be injected into other services to give them access to the configuration data

In its current form, the configuration service has some fairly rigid behavior.  (We hope to make it more dynamic in the future; for more info, see the [Hopes and Dreams](#hopes-and-dreams) section below.)  Here's how it works:

## Loading configuration data

All configuration data is read from config files on disk.  When launching a Trapperkeeper application, you specify a `--config` command-line argument, whose value is a file path or comma-separated list of file paths.  You may specify the path to a single config file, or you may specify a directory of config files. If no path is specified, Trapperkeeper will act as if you had passed it an empty configuration file.

We support several types of files for expressing the configuration data:

   * `.ini` files
   * `.edn` files (Clojure's [Extensible Data Notation](https://github.com/edn-format/edn) format)
   * `.conf` files (this is the [Human-Optimized Config Object Notation](https://github.com/typesafehub/config/blob/master/HOCON.md) format; a flexible superset of JSON defined by the [typesafe config library](https://github.com/typesafehub/config))
   * `.json` files
   * `.properties` files

The configuration service will then parse the config file(s.md) into memory as a nested map; e.g., the section headers from an `.ini` file would become the top-level keys of the map, and the values will be maps containing the individual setting names and values from that section of the ini file.  (If using `.edn`, `.conf`, or `.json`, you can control the nesting of the map more explicitly.)

Here's the protocol for the configuration service:

```clj
(defprotocol ConfigService
  (get-config [this] "Returns a map containing all of the configuration values")
  (get-in-config [this ks] [this ks default]
                 "Returns the individual configuration value from the nested
                 configuration structure, where ks is a sequence of keys.
                 Returns nil if the key is not present, or the default value if
                 supplied."))
```

Your service may then specify a dependency on the configuration service in order to access service configuration data.

Here's an example.  Assume you have a directory called `conf.d`, and in it, you have a single config file called `foo.conf` with the following contents

```conf
foosection1{
   foosetting1 = foo
   foosetting2 = bar
}
```

Then, you can define a service like this:

```clj
(defservice foo-service
   [[:ConfigService get-in-config]]
   ;; service initialization code
   (init [this context]
     (println
      (format "foosetting2 has a value of '%s'"
         (get-in-config [:foosection1 :foosetting2])))
     context))
```

Then, if you add `foo-service` to your `bootstrap.cfg` file and launch your app with `--config ./conf.d`, during initialization of the `foo-service` you should see:

    foosetting2 has a value of 'bar'

## Logging configuration

Trapperkeeper provides some automatic configuration for logging during application startup.  This way, services don't have to deal with that independently, and all services running in the same Trapperkeeper container will be able to share a common logging configuration.  The built-in logging configuration is compatible with `clojure.tools/logging`, so services can just call the `clojure.tools/logging` functions and logging will work out of the box.

The logging implementation is based on [`logback`](http://logback.qos.ch/).  This means that Trapperkeeper will look for a `logback.xml` file on the classpath, but you can override the location of this file via configuration.  This is done using the configuration setting `logging-config` in a `global` section of your configuration files.

`logback` is based on [`slf4j`](http://www.slf4j.org/), so it should be compatible with the built-in logging of just about any existing Java libraries that your project may depend on.  For more information on configuring logback, have a look at [their documentation](http://logback.qos.ch/manual/configuration.html).

For example:

```CONF
global {
   logging-config = /path/to/logback.xml
}
```
