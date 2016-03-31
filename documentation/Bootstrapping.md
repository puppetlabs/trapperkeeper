# Bootstrapping

As mentioned briefly on the [Quick Start](Trapperkeeper-Quick-Start.md) page, Trapperkeeper relies on a `bootstrap.cfg` file to determine the list of services that it should load at startup.  The other piece of the bootstrapping equation is setting up a `main` that calls Trapperkeeper's bootstrap code.  Here we'll go into a bit more detail about both of these topics.

## `bootstrap.cfg`

The `bootstrap.cfg` file is a simple text file, in which each line contains the fully qualified namespace and name of a service.  Here's an example `bootstrap.cfg` that enables the nREPL service and a custom `foo-service`:

```
puppetlabs.trapperkeeper.services.nrepl.nrepl-service/nrepl-service
my.custom.namespace/foo-service
```

Note that it does not matter what order the services are specified in; trapperkeeper will resolve the dependencies between them, and start and stop them in the correct order based on their dependency relationships.

In normal use cases, you'll want to simply put `bootstrap.cfg` in your `resources` directory and bundle it as part of your application (e.g. in an uberjar).  However, there are cases where you may want to override the list of services (for development, customizations, etc.).  To accommodate this, Trapperkeeper will actually search in three different places for the `bootstrap.cfg` file; the first one it finds will be used.  Here they are, listed in order of precedence:

  * a location or list of locations ([see here](Command-Line-Arguments.md#multiple-bootstrap-files)) specified via the optional `--bootstrap-config` parameter on the command line when the application is launched
  * in the current working directory
  * on the classpath

## Configuration

Bootstrapping determines _which_ services should be loaded, but it doesn't say _how_ they should be configured. For that, you'll want to learn about the [built-in service](Built-in-Services.md#configuration-service) that Trapperkeeper uses to read configuration data.
