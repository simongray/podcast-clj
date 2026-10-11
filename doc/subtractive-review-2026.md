# A subtractive review, October 2026

This review asks what the library can lose without a reader losing
anything: features that nothing uses or tests, public vars that aren't
API, and code that reads better shorter. It was made on 11 October 2026
at commit e23c2c0 in two passes: a count of what the code, the tests,
the inspector, the server and the docs use, from clj-kondo's analysis of
every var, and a reading of every namespace by the rules of the
subtractive review. Nothing was changed; each finding says what goes,
why nothing is lost, and its size, for a batch to apply on approval.


## What the count says

The library has 76 namespaces and 30,423 lines under `src`, with 1,026
public vars, of which 336 are `^:no-doc` and 690 are the documented API.

**No feature is unused and untested.** Every namespace is required by
another, by the inspector or by the server, except four that are leaves
for apps and hosts: `analytics` (476 lines), `value.metadata` (264),
`value.webln` (197, ClojureScript only) and `feed.schema` (164). All four
have tests. Every namespace is touched by a test, directly or through
its caller; `feed.check.facts` (279 lines) and `util.names` (22) are
tested only through `feed.check` and the parsers.

**No public var is dead.** Of the 690 documented public vars, 81 have no
caller outside their own namespace, in the code, the tests, the
inspector or the server:

- 46 are named in the docs and exist to be replaced by an app: the
  `default-limits` of nine namespaces, the `calls` tables of the
  directories, OP3, Podping and the gpodder API, `feed.spec/mediums`,
  `taxonomy` and `transcript-types`, `comments.nostr/relays`,
  `profile/default-preferences`, `feed.search/boosts` and `aliases`,
  `format.hls/types`, `value.nwc/kinds`, and the like. They're the
  "services are defaults" rule in practice, and they stay.
- 30 are named nowhere in the docs and used only inside their own
  namespace. They aren't API, and a reader of the reference page meets
  them as if they were. They're the candidates below.
- 5 are `declare` forms of private functions that the count took for
  definitions.

**What no test calls.** 53 documented public vars are used by other
namespaces but by no test of their own; nearly all are the small
functions of `feed.spec`, `util.bytes.view`, `util.time` and
`comments.common` that the parsers and the readers exercise on every
run, and the `readable?`, `requests`, `resumed` and `with-exchanges` of
the three comment protocols, which the comments tests reach through
`comments/fetch!`. Three are used by the inspector or the server alone:
`analytics.op3/origin`, `op3/prefixed?` and `app.library/unsynced?`.

### Public vars to mark `^:no-doc`

Each is used only in its own namespace and named in no doc. Marking it
`^:no-doc` takes it off the reference page and out of the promise of the
API, at no cost to a reader; removing the few that one function uses
once is the reading pass's call.

| Namespace | Vars |
|---|---|
| `feed.spec` | `list-mediums`, `social-protocols`, `chat-protocols`, `value-types`, `value-methods`, `recipient-types`, `service-slugs` (the enumerations the registry entries read; `mediums`, `taxonomy` and `transcript-types` are named in the docs and stay) |
| `feed.quirks` | `uri-variants`, `explicit-values`, `yes-no-values`, `transcript-type-values`, `normalizations`, `registry-normalizations` |
| `feed.validate` | `structural-rules` |
| `feed.resolve` | `timed-transcript-types` |
| `feed.discovery` | `podcast?` |
| `directory.podcastindex` | `auth-headers`, `value->block`, `feed->model` |
| `format.transcript` | `parse-srt`, `parse-text` (or name every per-format reader in the namespace doc, as `parse-json` is) |
| `value` | `fee?`, `time-split-at` |
| `value.lnaddress` | `sent-comment` |
| `value.metadata` | `header-name` |
| `value.nwc` | `info-request` |
| `nostr` | `max-name-length` |
| `util.bech32` | `max-length` (a default an option replaces, so it may rather belong in the docs) |
| `app.feeds` | `default-concurrency` (the same) |
| `app.library` | `saved-keys` (the same) |

An hour for the lot, with the reference page regenerated.


## What the reading says

Two readers took the library in halves, in the order of the layers, and
the lead re-checked their findings in the code before they went in. The
code is in good shape on the rules of the review: functions are small,
nearly every helper has two or more callers, no option is unexercised,
and the long docstrings state rules of a spec or an option list rather
than the code. What there is to take away is mostly the same small
function written in several namespaces.

### The upper half: the app, the profile, sync, comments, payments, directories

**One function in several places.** Each of these is a short function
written again wherever it was needed, and one shared var serves them all;
about twenty copies become seven functions.

- The error map `{:error (diagnostics/describe d)}` is a `^:no-doc`
  `failure` in `value.lnaddress`, `value.webln` and `value.nwc` and a
  closure in `value.metadata`. One function in `diagnostics`, beside
  `describe` and `fetch-failure`.
- Unix seconds, `(quot (inst-ms x) 1000)`, in eight places, once with a
  platform branch in `directory.podcastindex` that `time/now` already
  hides. One `time/unix-seconds`.
- The trailing-slash stripper, the regex `(^|[^/])/+$`, in eight
  namespaces of both halves. One function in wary-fetch's `url`, where
  the URL work lives, and a regex that's easy to get wrong once is
  written once.
- "Is a Lightning node key" in `value.nwc`, `value.webln` and
  `value.lnaddress`, which all require `value.payment`; one `node-key?`
  there.
- `nwc/key-text?` is `nostr/hex-id?` to the character, and `nwc` requires
  `nostr`.
- "Is a finite number" as four closures in `profile`, `profile.marks`,
  `directory.links` and `sync.gpodder`, and once more in `feed.coerce`;
  make that one public.
- "The map without the entries that say nothing" as `comments.common`'s
  `present`, a private `present` in `directory`, and four
  `(into {} (remove (comp nil? val)) …)` forms; one public function.
- "Try to read a Podcast Index answer, else nil" as a private function in
  `directory.podcastindex` and inline in `app.episode` and `app.show`;
  make it public as `try-response`, the name the project gives such
  functions.
- A whole-buffer UTF-8 decode as two private helpers and one inline
  form; a two-argument arity of `bytes/decode`.
- The closure that keeps a gpodder device's cursors between syncs, in the
  server, in the inspector and in a docstring of the gpodder server; one
  function in `sync` that gives it.

**Two implementations of two fetchers.** `podcastindex/fetch-remote-value!`
and `fetch-remote-item!` are called by nothing but their tests and
named in two docs, while `app.episode` and `app.show` fetch the same
things again with the same requests, plus the clock option, `try-send!`
and a reason in the error. Either the pair gains those and the app layer
calls them, or the pair goes and the docs point at the request builders
and `try-response`. This is the one real duplication of a feature.

**Per file, worth a batch.**

- `comments.activitypub/read-api` rebuilds its own `noted` update inline
  in the problem branch; four lines and one path fewer.
- `value.nwc/send-command!` calls `unsupported` twice, as the test and
  as the value; bind it once.
- `sync.gpodder.server/new-session` keeps a platform branch and a comment
  on `Math.random` that `(bytes/hex (bytes/generate! 16))` replaces, as
  the inspector already does.
- `app.library` writes `(-> x (reindexed guid) (kept guid keep-fn))` four
  times; one private sibling of `tidied`.
- `sync.gpodder/pulled` applies the changes and writes the report in one
  body; a `pull-report` lets it read as apply, then report.
- The gpodder server's `ring`, the inspector's `html` and `text` and the
  server's `refused` each build a Ring response by hand; `util.ring` is
  the home of one.
- The inspector's `page/link` is `url/web-url?` written as a regex.
- The inspector's `local-only` and the server's `known-names` are the
  same Host check; a shared one in `util.ring` would serve both, though
  they're separate programs.

**The author's call.** `profile/played-mark` and `profile/migrated` move a
played mark written by an earlier build of the profile, and the
inspector's `migrate-episodes!` moves an earlier shape of its player
state. If no such data exists outside this repository, both shims go
now; if it does, version 2 of the saved format, which the identity design
plans, is when they go. And `comments/next-page` is the one function of
the comments facade that doesn't dispatch by protocol; either it
dispatches like its siblings or `send-round!` calls the ActivityPub one
directly.

**What should stay, though it looks removable.** The pure request builders
with no caller outside, `value.metadata/request`, `value.nwc/info-request`
and `lnaddress`'s requests, since an app with its own HTTP client calls
them; the tables an option replaces; `value/report` as the fourteenth
`report` accessor, which is the project's convention; `podping/call-node!`
parsing a body twice, which keeps the readers pure; the five
`*-problem` checks of the profile without docstrings, which the `checks`
docstring covers; and the "Deferred." that ends eleven docstrings, which
is how the library marks a function that gives a future or a promise, not
a stray word.

### The lower half: feeds, formats, parsers and utilities

The same picture: no dead function, no unreachable branch, comments that
say why, and about two dozen small findings, none large.

**The three biggest.**

- `format.media.mp3` rebuilds the reading context that
  `format.media.id3` exports for it: `id3/context`, `note!` and `quirk?`
  are `^:no-doc` public and nothing outside `id3` calls them, while
  `mp3` writes the same three again. Either `mp3` calls them, which is
  what their being public implies, or they're private helpers in
  disguise.
- `mp4/config` is `mp4/child` with two arguments swapped; delete it and
  call `child`. Six lines.
- `schedule/next-check` reimplements `time/latest`, which `app.feeds` and
  `app.library` already use for the same "no earlier than not-before":
  five lines become one.

**Copies across namespaces.**

- The trailing-slash regex with its two-line comment on quadratic
  backtracking, in `feed.guid`, `feed.quirks` and `feed.discovery`, and
  five more times in the upper half.
- The BYDAY regex, the FREQ list and the weekday list, rewritten in
  `feed.schema` instead of read from `util.time.rrule`; with
  `byday-pattern` anchored, the schema can't drift from the parser.
- A `diagnostic` helper in five namespaces (`feed.validate`,
  `format.chapters`, `format.transcript`, `format.media.id3`,
  `feed.check.rules`); one in `diagnostics` would serve all, at the cost
  of some 45 call sites, so only in a batch that touches those files
  anyway.
- "The map without its nil entries", ten ways across the library, four
  of them here; `comments.common/present` already has the widest
  meaning, and one function at the bottom layer serves all ten.

**Per file, worth a batch.**

- `util.bytes.view/part` and `reading` share one try/catch that also
  catches the stack overflow of deep input; a private `guarded` holds the
  rule once.
- `crypto.secp256k1/shared-x` lifts the public key twice and `verify`
  repeats the check by hand; a private `key-point` serves the three, one
  modular square root fewer per shared secret, and `one` replaces three
  `(big "1")`.
- `feed.repair` spells "the reference names a legal code point" twice.
- `feed.coerce`'s `:iso8601` branch is `time/parse-date` with its two
  branches swapped, and the two patterns can't both match one string.
- `feed.resolve/artwork` and `poster` sort by the same width lambda; a
  private `largest`.
- `feed.discovery` builds the same signing map twice.
- `format.opml/outlines` scans the attributes that `subscription`
  re-reads two lines later.
- `downloads/rule-problem` computes `criteria/problem` twice, as the test
  and the value of one clause.
- `format.media.id3` threads a text width that follows from the encoding
  everywhere; one parameter fewer across some twenty lines, optional.
- `format.media.mp4/read-movie` holds the duration decision as five
  locals and a `cond`; a private `movie-duration` lets it read as the
  parts it assembles, optional.

**Public interface.** Two function-valued options lack the suffix the
rest of the library uses, `downloads/plan`'s `:estimate` and
`publish/for-subscriber`'s `:with-token`, against `:now-fn`,
`:page-url-fn`, `:allow-pred` and the others. `resolve/transcript-preference`
is `^:no-doc` though it's the default of `:types`, which the rule on
defaults makes public. `format.image` has two of its three byte readers
and `unless-broken` public and the third private, none with an outside
caller; either way is consistent, the mix isn't. Two documented option
forms have no caller and no test, `:requirements` as a map in `validate`
and `check/report`, and `:block? false` in `for-subscriber`; each is a
line of code and a line of docstring, to keep or drop on the author's
word.

**What should stay.** The ten one-line `report` functions, each the API
of its namespace with callers of its own; the `^:no-doc` byte readers of
`id3` and `mp4`, which the tests reach through `read` and which have
standalone meaning; every spec table; `view/need` beside `need-all`;
`transcript/paragraphs` and `blocks` as two functions, since they differ
on lines of spaces; `podping/call-node!` parsing twice; and
`resolve/notes-url`, 37 lines of URL repair for show notes that sit in
the wrong namespace but have no better home inside the repository.
Four tables name the same seven transcript types across the layers with
no test tying them; a three-line assertion would catch drift.


## What to apply first

1. The thirty `^:no-doc` marks and the regenerated reference page. An
   hour.
2. The seven shared functions that replace about twenty copies: the
   error map, Unix seconds, the trailing-slash stripper, the node key
   check, `finite?`, `present`, `try-response`, the UTF-8 decode and
   `sync/stepper`, with the copies removed. A day, over both halves.
3. The per-file tidies that remove a path or a double call: `read-api`,
   `send-command!`, `new-session`, the `settled` helper of `app.library`,
   `mp4/config`, `schedule/next-check`, `rule-problem`, `shared-x`,
   `outlines`, the repair spelling, the coerce branch, `largest` and the
   discovery signing map. A day.
4. The two fetchers of the Podcast Index: one implementation, either the
   documented pair with the app layer's options or the request builders
   alone. Half a day, with the docs.
5. The naming of the two options and the public or private line in
   `format.image`, with the migration shims and the comments proxy on
   the author's word. An hour, plus the decisions.
6. The optional ones for a batch that touches those files anyway: the
   shared `diagnostic`, the id3 text width, `movie-duration`, the
   `pull-report`, the Ring response builder in `util.ring` and the shared
   Host check.
