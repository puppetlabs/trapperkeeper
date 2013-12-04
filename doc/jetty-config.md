## Configuring The Jetty 7 Webserver

The `[jetty]` section in an `.ini` configuration file configures an embedded
Jetty HTTP server inside trapperkeeper.

### `host`

This sets the hostname to listen on for _unencrypted_ HTTP traffic. If not
supplied, we bind to `localhost`, which will reject connections from anywhere
but the server process itself. To listen on all available interfaces,
use `0.0.0.0`.

### `port`

This sets what port to use for _unencrypted_ HTTP traffic. If not supplied, we
won't listen for unencrypted traffic at all.

### `max-threads`

This sets the maximum number of threads assigned to responding to HTTP and HTTPS
requests, effectively changing how many concurrent requests can be made at one
time. Defaults to 50.

> **Note:** Due to how Jetty 7 behaves, this setting must be higher than the
 number of CPU's on your system or it will stop processing any HTTP requests.

### `ssl-host`

This sets the hostname to listen on for _encrypted_ HTTPS traffic. If not
supplied, we bind to `localhost`. To listen on all available interfaces,
use `0.0.0.0`.

### `ssl-port`

This sets the port to use for _encrypted_ HTTPS traffic. If not supplied, we
won't listen for encrypted traffic at all.

### `ssl-cert`

This sets the path to the server certificate PEM file used by the web
service for HTTPS.

> **Note:** This setting overrides the alternate configuration settings
`keystore` and `key-password`.

### `ssl-key`

This sets the path to the private key PEM file that corresponds with the
`ssl-cert`, it used by the web service for HTTPS.

> **Note:** This setting overrides the alternate configuration settings
`keystore` and `key-password`.

### `ssl-ca-cert`

This sets the path to the CA certificate PEM file used for client
authentication. Authorized clients must be signed by the CA that that
corresponds to this certificate.

> **Note:** This setting overrides the alternate configuration settings
`truststore` and `trust-password`.

### `keystore`

This sets the path to a Java keystore file containing the key and certificate
to be used for HTTPS.

### `key-password`

This sets the passphrase to use for unlocking the keystore file.

### `truststore`

This describes the path to a Java keystore file containing the CA certificate(s)
for your infrastructure.

### `trust-password`

This sets the passphrase to use for unlocking the truststore file.

### `certificate-whitelist`

Optional. This describes the path to a file that contains a list of certificate
names, one per line.  Incoming HTTPS requests will have their certificates
validated against this list of names and only those with an _exact_ matching
entry will be allowed through. (For a puppet master, this compares against the
value of the `certname` setting, rather than the `dns_alt_names` setting.)

If not supplied, trapperkeeper uses standard HTTPS without any additional
authorization. All HTTPS clients must still supply valid, verifiable SSL client
certificates.

### `cipher-suites`

Optional. A comma-separated list of cryptographic ciphers to allow for incoming
SSL connections. Valid names are listed in the
[official JDK cryptographic providers documentation](http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html#SupportedCipherSuites);
you'll need to use the all-caps cipher suite name.

If not supplied, trapperkeeper uses the default cipher suites for your local
system on JDK versions older than 1.7.0u6. On newer JDK versions, trapperkeeper
will use only non-DHE cipher suites.

### `ssl-protocols`

Optional. A comma-separated list of protocols to allow for incoming SSL
connections. Valid names are listed in the
[official JDK cryptographic protocol documentation](http://docs.oracle.com/javase/7/docs/technotes/guides/security/SunProviders.html#SunJSSEProvider);
you'll need to use the names with verbatim capitalization.
For example: `SSLv3, TLSv1, TLSv1.1, TLSv1.2`.

If not supplied, trapperkeeper uses the default SSL protocols for your local
system.

> **Note:** This setting is only effective when trapperkeeper is running with
Java version 1.7 or better.
