# Built-in Services

Trapperkeeper includes a handful of built-in services that are intended to remove some of the tedium of tasks that are common to most applications.  There is a configuration service (which is responsible for loading the application configuration and exposing it as data to other services), a shutdown service (which provides some means for shutting down the container and allows other services to register shutdown hooks), and an optional nREPL service (which can be used to run an embedded REPL in your application, so that you can connect to it from a remote process while it is running).

There are some other basic services available that don't ship with the Trapperkeeper core, in order to keep the dependency tree to a minimum.  Of particular interest is the [webserver service](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9), which you can use to run clojure Ring applications or java servlets.

Detailed information about Trapperkeeper's built-in services can be found on the following pages:

- [Configuration Service](Built-in-Configuration-Service.md)
- [Shutdown Service](Built-in-Shutdown-Service.md)
- [nREPL Service](Built-in-nREPL-Service.md)
