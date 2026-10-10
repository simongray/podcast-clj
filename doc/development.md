# Development

```bash
clojure -M:dev:nrepl                  # nREPL on port 7888
clojure -X:test                       # all tests on the JVM
clojure -X:test-floor                 # the library tests on Clojure 1.11
npm install                           # once, for the ClojureScript tests
clojure -M:cljs compile test          # all tests in Node
clojure -M:cljs compile browser-test  # all tests in a browser: serve target/browser-test
clojure -T:build jar                  # the jar and its pom, in target/
```

Most tests are `.cljc` files, and they run on the JVM and in ClojureScript.
The corpus tests read real feeds from `dev-resources/corpus/` (not checked in
to Git). To download the corpus, run `(corpus/download!)` from `dev/corpus.clj`.

To change [wary-fetch](https://github.com/simongray/wary-fetch),
[qname-hiccup](https://github.com/simongray/qname-hiccup),
[html-pieces](https://github.com/simongray/html-pieces),
[drop-in-search](https://github.com/simongray/drop-in-search) or
[mind-meld](https://github.com/simongray/mind-meld) and podcast-clj at
once, check them out beside this one, in `../wary-fetch`,
`../qname-hiccup`, `../html-pieces`, `../drop-in-search` and
`../mind-meld`, and add the
`:local` alias, e.g.
`clojure -M:dev:local:nrepl`. Once the change is pushed, put its commit in
deps.edn, or its version once it's released on Clojars.


## The inspector

The inspector in `web/` is an example app that uses only the public API.
It shows the model, the report and the validation of any feed,
and it has a small player that syncs. To start it on http://localhost:8300/:

```bash
clojure -M:cljs compile player  # once: the browser player
clojure -M:web
```
