# Development

```bash
clojure -M:dev:nrepl                  # nREPL on port 7888
clojure -X:test                       # all tests on the JVM
clojure -X:test-floor                 # the library tests on Clojure 1.11
npm install                           # once, for the ClojureScript tests
clojure -M:cljs compile test          # all tests in Node
clojure -M:cljs compile browser-test  # all tests in a browser: serve target/browser-test
node test/browser-run.mjs target/browser-test  # or run them in a headless Chrome
clojure -T:build jar                  # the jar and its pom, in target/
```

Most tests are `.cljc` files, and they run on the JVM and in ClojureScript.
The corpus tests read real feeds from `dev-resources/corpus/` (not checked in
to Git). To download the corpus, run `(corpus/download!)` from `dev/corpus.clj`.
To measure a larger sample of real feeds, see
[the corpus study](background/corpus-study-2026.md) and `dev/study.clj`. The
conformance test checks the rules of the parse, the validator and the
refresh against the feeds that the study chose as exhibits, when its
sample is in `dev-resources/corpus/study/`. To time the
core of the library over the corpus before and after a change, run
`(bench/run!)` from `dev/bench.clj`, and `(bench/compare before after)`
with the dates of two runs. For the memory that a library holds with all
its feeds and with none, run `(bench/footprints)`.

To change a sibling library and podcast-clj at once, use the `:local`
alias, e.g. `clojure -M:dev:local:nrepl`. It takes each sibling that it
lists in deps.edn from a checkout beside this one, e.g. `../wary-fetch`,
so all of them must be checked out. Once the change is pushed, put its
commit in deps.edn, or its version once it's released on Clojars.


## The inspector

The inspector in `web/` is an example app that uses only the public API.
It shows the model, the report and the validation of any feed,
and it has a small player that syncs. To start it on http://localhost:8300/:

```bash
clojure -M:cljs compile player  # once: the browser player
clojure -M:web
```

It's for your own machine: it listens on localhost and answers no other
name, and it has no login, since anyone who reaches it can sync and pay
through it. It fetches feeds and their files from the public internet
only, so a page that posts a URL to it can't reach your network.
