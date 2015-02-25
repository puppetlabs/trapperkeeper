<img src="http://images4.fanpop.com/image/photos/21500000/4x12-Trapper-Keeper-south-park-21568387-720-540.jpg"
 alt="Trapperkeeper logo" title="hold it" align="right" height="300px" />

# Trapperkeeper

[![Join the chat at https://gitter.im/puppetlabs/trapperkeeper](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/puppetlabs/trapperkeeper?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

[![Build Status](https://travis-ci.org/puppetlabs/trapperkeeper.png?branch=master)](https://travis-ci.org/puppetlabs/trapperkeeper)

Trapperkeeper is a Clojure framework for hosting long-running applications and services.
You can think of it as a sort of "binder" for Ring applications and other modular bits of Clojure code.

## Installation

Add the following dependency to your `project.clj` file:

[![Clojars Project](http://clojars.org/puppetlabs/trapperkeeper/latest-version.svg)](http://clojars.org/puppetlabs/trapperkeeper)

## Community

* Bug reports and feature requests: you can submit a Github issue, but we use [JIRA](https://tickets.puppetlabs.com/browse/TK) as our main issue tracker.


## Documentation

You can find a quick-start, example code, and lots and lots of documentation on our:

* [Wiki](https://github.com/puppetlabs/trapperkeeper/wiki)


## Lein Template

A Leiningen template is available that shows a suggested project structure:

    lein new trapperkeeper my.namespace/myproject
    
Once you've created a project from the template, you can run it via the lein alias:

    lein tk

Note that the template is not intended to suggest a specific namespace organization;
it's just intended to show you how to write a service, a web service, and tests
for each.


## License

Copyright © 2013 Puppet Labs

Distributed under the [Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)
