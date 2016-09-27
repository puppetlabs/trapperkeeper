# Experimental Feature: Restart File

When using Trapperkeeper apps inside of packages, it is convenient for a service
framework to have a clear indication as to when all of the Trapperkeeper
services in the app have been started -- as opposed to just knowing when the
Java process hosting the app has been spawned.  The "restart file" feature in
Trapperkeeper provides this capability.  As a reference example, the
[EZBake](https://github.com/puppetlabs/ezbake) build system for
Trapperkeeper-based applications makes use of the "restart file" feature.  Its
service packages can pause a hosting service framework (SysVinit, systemd)
during a "service start" attempt until the app's services have all been started,
or have failed to start.

The "restart file" is considered to be a somewhat experimental feature in that
the implementation may change in a future release.

## Implementation Details

Each time Trapperkeeper has successfully finished processing all of the start
calls that it makes to each of the services in an application -- both at Java
process start and after a service reload is requested -- it increments a
counter in a file on disk.  The location of the file is controlled by the value
of the `restart-file` setting.

If the value in the file before services are started is '3', for example, the
value will be updated to '4' after services have been started.  If the file does
not exist at the time services have been started, the value is written as '1'.
The value rolls back around to '1' if the value would be incremented beyond the
maximum value for a `java.lang.Long` or if the contents of the file is otherwise
unable to be parsed as an integer.

In terms of using the restart file as an indication that services have been
started -- for example, from a background script that accesses the file in a
polling loop to determine when the start phase has finished -- it is best to
just look for a change to the contents of the file rather than having any
specific logic that interprets the integer values.  As noted earlier, the
nature of the 'start' marker may change in a future release.

## Configuration Details

The `restart-file` setting can be specified either via a command line argument
to Trapperkeeper...

```
-r | --restart-file /write/file/here
```

... or as a setting under the "global" section of a Trapperkeeper configuration
file.  For example, a HOCON-formatted "global.conf" might have:

```
global: {
  restart-file: /write/file/here
}
```

In the event that the `restart-file` setting were specified both as a command
line argument and within the "global" section of a Trapperkeeper configuration
file, the value specified on the command line would be the one in which the
'start' counter is incremented.

If a value for the `restart-file` setting is not specified via either the
command line or within the "global" section of a Trapperkeeper configuration
file, Trapperkeeper will not write a 'start' counter to any file.
