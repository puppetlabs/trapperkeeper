# trapperkeeper

A Clojure library designed to ... well, that part is up to you.

## Usage

FIXME

## Using Our Test Utils

Trapperkeeper provides [utility code](./test/puppetlabs/trapperkeeper/testutils) for use in tests.
The code is available in a separate "test" jar that you may depend on by using a classifier in your project dependencies.

```clojure
  (defproject yourproject "1.0.0"
    ...
    :profiles {:test {:dependencies [[puppetlabs/trapperkeeper "x.y.z" :classifier "test"]]}})
```

## License

Copyright © 2013 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
