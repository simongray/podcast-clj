# Principles

These principles guide the design of podcast-clj, its API and its code.
[Architecture](architecture.md) explains how each part is built.


## General

- **A library, not a framework.** Pure functions over plain Clojure data,
  with no global state. Your app decides how to store data, play media and
  draw its UI.
- **Everything a podcast app needs.** A feature is left out only when it
  makes no sense in a library, like audio processing, or when it would be
  an immense amount of work.
- **Standards first.** Each spec is implemented as written. New features
  are built on what the specs already offer, including the parts that most
  apps ignore: OPML has categories for tags and includes for followed
  lists, so podcast-clj uses them. We only invent where the specs leave a
  gap.
- **Lenient, but transparent.** Real feeds break the specs in many small
  ways, and an app has to show them anyway. Each workaround is a named
  quirk that can be switched off for strict parsing, and every repair goes
  in a report. Nothing is dropped silently: unknown elements are kept as
  Hiccup, so `emit` writes back the content that `parse` read, and the
  report names anything that couldn't be kept.
- **Sync without a server.** The listener's profile is a CRDT, so devices
  merge their changes in any order, and the later of two changes wins. Any
  place that can hold files is enough to sync through, even a plain folder.
- **One codebase.** Almost everything is `.cljc` and runs on the JVM, in
  Node and in the browser. Platform code lives in small adapters, and in
  ClojureScript a function that sends requests returns a promise.
- **Tested on real feeds.** The tests run against a corpus of real feeds,
  not only hand-written fixtures.


## API

- **Compose, don't reimplement.** The `app.*` namespaces are built from the
  lower-level functions, so an app can start with one call and drop down a
  level when it needs more control.
- **Convention over configuration.** A function requires only what it
  can't work without. Everything else has a default, and the options are
  a map that comes last and can be left out, which a test checks.
- **The feed model is data.** One tag registry describes every element.
  The parser, the emitter, the validator, the Malli schemas and the
  reference docs all read it, and an app can extend it with its own
  elements.
- **The app's state is one map.** The subscriptions, feeds, queue and play
  progress of an app are kept in one map that fits in an atom, and each
  change returns the new map along with what it did.
- **Pure unless marked.** Only functions that end in `!` do I/O. A function
  that needs the time takes it, except those that end in `!` and the
  high-level ones, which read a clock that a test can fix. Requests are
  plain maps built and read by pure functions, so any HTTP client can send
  them.
- **Errors are data.** Functions that do I/O return their failures as data
  instead of throwing, so one failed request doesn't lose the rest. Only
  the low-level functions that send a single request throw.
- **Services are defaults.** What podcast-clj knows about a service, such
  as its address or limits, is a default that an option can replace. An
  app shouldn't need a new release of podcast-clj when a service moves or
  closes.
- **Light on the network.** Feeds are polled with conditional requests and
  media files are read with byte ranges, so podcast-clj fetches no more
  than it needs.
- **Safe by default.** Every fetch has limits of size and time, URLs from
  untrusted sources are only fetched if they're on the public internet,
  and secrets print as `#<secret>`.


## Code

- **Layers without cycles.** A namespace only requires namespaces in its
  own [layer](architecture.md#layers) or below, e.g. `util` and `crypto`
  are at the bottom and know nothing about podcasts. A test enforces it.
- **Cite the spec.** Where code implements a standard, a short comment or
  the docstring names the spec and its section.
