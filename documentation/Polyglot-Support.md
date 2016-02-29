# Polyglot Support

It should be possible (when extenuating circumstances necessitate it) to integrate code from just about any JVM language into a Trapperkeeper application.  At the time of this writing, the only languages we've really experimented with are Java and Ruby (via JRuby).

For Java, the Trapperkeeper webserver service contains an [example servlet app](https://github.com/puppetlabs/trapperkeeper-webserver-jetty9/tree/master/examples/servlet_app), which illustrates how you can run a Java servlet in trapperkeeper's webserver.

We have also included a simple example of wrapping a Java library in a Trapperkeeper service, so that it can provide functions to other services.  Have a look at the code for the [example Java service provider app](https://github.com/puppetlabs/trapperkeeper/tree/master/examples/java_service) for more info.

For Ruby, we've been able to write an alternate implementation of a `webserver-service` which provides an `add-rack-handler` function for running Rack applications inside of Trapperkeeper.  We've also been able to illustrate the ability to call clojure functions provided by existing clojure Trapperkeeper services from the Ruby code in such a Rack application.  This code isn't necessarily production quality yet, but if you're interested, have a look at the [trapperkeeper-ruby project on github](https://github.com/puppetlabs/trapperkeeper-ruby).
