## Configuring the nREPL service

The `[nrepl]` section in a _trapperkeeper_ `.ini` configuration file specifies all the settings
needed to start up an `nREPL` server attached to _trapperkeeper_.

### `enabled`

The `enabled` flag is a boolean value, which can be set to either `"true"` or `"false"`. When
this is set to true, the `nREPL` server will start and accept connections. If this value is
not specified then `enabled=false` is assumed.

### `host`

The IP address to bind the nREPL server to. If not specified then `0.0.0.0` is used, which
indicates binding to all available interfaces.

### `port`

The port that the `nREPL` server is bound to. If no port is defined then the default value
of `7888` is used.

## Typical `config.ini` for nREPL

```ini
[nrepl]
port = 12345
enabled = true
```

## The `nREPL` server

For more information on the nREPL server see https://github.com/clojure/tools.nrepl/blob/master/README.md .

