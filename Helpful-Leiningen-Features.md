# Helpful Leiningen Features

There's nothing really special about developing a Trapperkeeper application as compared to any other Clojure application, but there are a couple of things we've found useful:

### Leiningen's `checkouts` feature

Since Trapperkeeper is intended to help modularize applications, it also increases the likelihood that you'll end up working with more than one code base/git repo at the same time.  When you find yourself in this situation, Leiningen's [checkouts](http://jakemccrary.com/blog/2012/03/28/working-on-multiple-clojure-projects-at-once/) feature is very useful.

### Leiningen's `trampoline` feature

If you need to test the shutdown behavior of your application, you may find yourself trying to do `lein run` and then sending a CTRL-C or `kill`.  However, due to the way Leiningen manages JVM processes, this CTRL-C will be handled by the lein process and won't actually make it to Trapperkeeper.  If you need to test shutdown functionality, you'll want to use `lein trampoline run`.

However, one quirk that we've discovered is that it does not appear that lein's `checkouts` and `trampoline` features work together; thus, when you run the app via `lein trampoline`, the classpath will not include the projects in the `checkouts` directory.  Thus, you'll need to do `lein install` on the `checkouts` projects to copy their jars into your `.m2` directory before running `lein trampoline run`.
