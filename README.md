# trapperkeeper

A Clojure library designed to ... well, that part is up to you.

### Implementation Questions
* Life cycle for startup? Or just expect services to start during graph compile?
  - Use atoms for implementation?
* How to handle command-line args?
  - Force services to provide required/supported CLI args?
  - Use protocols? Or require additional functions in graphs?
  - How to resolve conflicts in service CLI args?
* Use with-redefs for common functions?

### Prismatic Graph
* http://github.com/Prismatic/plumbing/blob/master/test/plumbing/graph_examples_test.clj
* http://blog.getprismatic.com/blog/2012/10/1/prismatics-graph-at-strange-loop.html
* http://blog.getprismatic.com/blog/2013/2/1/graph-abstractions-for-structured-computation
* http://www.infoq.com/presentations/Graph-Clojure-Prismatic
* http://highscalability.com/blog/2013/2/14/when-all-the-programs-a-graph-prismatics-plumbing-library.html

## Usage

FIXME

## License

Copyright © 2013 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
