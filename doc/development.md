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
The corpus tests read real feeds from `dev-resources/corpus/`, which isn't
in Git. To download the corpus, run `(corpus/download!)` from
`dev/corpus.clj`.

To measure a larger sample of real feeds, see [the corpus
study](background/corpus-study-2026.md) and `dev/study.clj`. When the
study's sample is in `dev-resources/corpus/study/`, the conformance test
checks the rules of the parser, the validator and the refresh against the
feeds that the study chose as exhibits.

To time the core of the library over the corpus before and after a
change, run `(bench/run!)` from `dev/bench.clj`, and then `(bench/compare
before after)` with the dates of the two runs. For the memory that a
library holds with all its feeds and with none, run `(bench/footprints)`.

To change a sibling library and podcast-clj at once, use the `:local`
alias, e.g. `clojure -M:dev:local:nrepl`. It takes each sibling that it
lists in deps.edn from a checkout beside this one, e.g. `../wary-fetch`,
so all of them must be checked out. Once the change is pushed, put its
commit in deps.edn, or its version once it's released on Clojars.


## On devices

The tests in `dev/devices/` run podcast-clj from this checkout in an
Electron app, and in a Capacitor app and an Expo app in an iOS simulator.
Each checks the library on a real feed, the document stores, and requests
to a local server: redirects, limits, bodies and the User-Agent. Run one
with:

```bash
dev/devices/run.sh electron
dev/devices/run.sh capacitor
dev/devices/run.sh expo
```

The script builds the app, runs it, prints each check and exits with 1
when one fails. CI runs the Electron test on every push. The iOS tests
need Xcode with an iOS simulator, and Expo needs CocoaPods too, e.g. from
`brew install cocoapods`. The first run of
each installs its npm packages and makes its iOS project, which takes a
few minutes. Run them before a release that changes how podcast-clj or
its siblings send requests or keep documents.


## Releases

The version comes from the Git tag on the commit. To release, set the
version in the install snippets of the README and the quickstart, commit,
push and wait for CI. Then tag the commit, e.g. `git tag -a v0.1.2 -m
0.1.2`, push the tag and run `clojure -T:build deploy`. It refuses a
commit without a version tag, with changes, or with a snippet that names
another version.


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
