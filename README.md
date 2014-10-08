# Trapperkeeper

[![Build Status](https://travis-ci.org/puppetlabs/trapperkeeper.png?branch=master)](https://travis-ci.org/puppetlabs/trapperkeeper)

Trapperkeeper is a Clojure framework for hosting long-running applications and services.
You can think of it as a sort of "binder" for Ring applications and other modular bits of Clojure code.

## Installation

Add the following dependency to your `project.clj` file:

[![Clojars Project](http://clojars.org/puppetlabs/trapperkeeper/latest-version.svg)](http://clojars.org/puppetlabs/trapperkeeper)

## Community

* Bug reports and feature requests: you can submit a Github issue, but we use [JIRA](https://tickets.puppetlabs.com/browse/TK) as our main issue tracker.
* freenode: #trapperkeeper


## Documentation

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
