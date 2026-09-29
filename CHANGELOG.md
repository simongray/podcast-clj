# Changelog

## Unreleased

- RSS 2.0, the `podcast` namespace of Podcasting 2.0, and the iTunes, Google
  Play, Spotify, Media RSS, RawVoice and Podlove Simple Chapters
  vocabularies. `parse` reads them into plain data, and `emit` writes them
  back in canonical form.
- One tag registry drives the parser, the emitter, the validator, the Malli
  schemas and the generated reference. Each interpreter takes the registry
  under `:registry`, so an app's own elements work like the standard ones.
- A quirks layer that repairs malformed feeds, recognizes namespace URI
  variants, and normalizes common deviations. Each step goes into the
  report, and `:except` switches rules off. jsoup is an optional lenient
  parser for the last resort.
- A catalogue of all diagnostics, with level and message. `summarize` groups
  a report by kind.
- `resolve`: one answer per property where the vocabularies overlap, with
  the inheritance rules of the specifications. Also `properties`, `sources`,
  the listening order, `seasons`, `blocked?`, `next-episode` from RFC 5545
  rules, and `next-check` for polling.
- Companion formats: JSON chapters, Podlove Simple Chapters, and chapters
  from timestamps in show notes. SRT, WebVTT, JSON, HTML and plain-text
  transcripts. OPML. The `podcast:guid` derivation. Value-for-value
  arithmetic and the boostagram record. HTML in descriptions as sanitized
  Hiccup, text and Markdown.
- `podcast/fetch!` on the JVM: a URL to a parsed feed in one step. It
  returns the validators for the next poll, the body and the new feed URL.
- The directories Apple Podcasts, fyyd, gpodder.net and the Podcast Index,
  with one shape for all results. Requests are maps, signed at a time that
  the caller gives. `combine` gives one entry per show. `search!` and
  `trending!` on the JVM.
- Podping: Hive calls as request maps, pings read from blocks, writers
  verified, `poll!` for a poller, and `notify-request` for a host.
- The player state as a state-based CRDT: a ranked queue, progress and
  played marks, subscriptions with settings per field, and a device
  registry. A hybrid logical clock stamps each write. `merge` is the join,
  `compact` removes the tombstones that all devices know, and `step` handles
  player events.
- `search`: episodes filtered and ordered by criteria as data, and a
  full-text index over subscriptions, ranked by BM25, on both platforms.
- Sync through any document store, with one document per device or one
  shared document. Memory, folder and WebDAV stores, an encrypting wrapper,
  a WebDAV server for any store, `store/check!`, `sync/next-at`, and `async`
  for code that runs on both platforms.
- The same code and tests on the JVM, in Node and in a browser. A test of
  the architecture keeps each require pointing down the layers. The
  inspector is a reference web app, with a browser player that syncs through
  its own WebDAV folder.
- Hostile input takes linear time and bounded stack. No regular expression
  backtracks quadratically. Elements nest at most `xml/max-depth` levels in
  XML and `html/max-depth` in HTML. `:timeout-seconds` bounds the whole
  HTTP exchange. `sanitize` reads a URL the way a browser does.
  `sync/read-document` refuses a state that `player/problem` finds fault
  with, and an envelope asks for at most `encrypted/max-iterations`.
- Stores, servers and nodes that fail do not give wrong results. A
  conditional put in the memory and folder stores is one step. A document
  that a store lost is written again, and a document listed without a
  version is always fetched. `sync!` refuses backends with the same name.
  The WebDAV server limits the size of a put and accepts `If-Match: *`.
  Podping goes to the next node when a node answers with an error, and
  `poll!` reads again the blocks that a node left out. A directory that
  refuses a request is a failure, not an answer with no shows.
- Odd values in a feed give no exception and no wrong answer. A date that
  does not exist does not read, and an unknown zone reads as UTC. An
  integer that JavaScript cannot hold exactly does not read, on the JVM
  either. `next-check` ignores items dated in the future and waits at most
  a year. The chapter and transcript readers take any text. A no-break
  space is whitespace, and a space between two inline elements stays. The
  repairs remove junk before and after the document, as a PHP warning
  leaves it.
- `validate` checks the type of each value, not only that it read, and
  reports negative numbers and keys that the registry does not define,
  such as `:rss/titel`. `emit` leaves out nil values and writes a value of
  another type as it is: `:no` stays no. It writes back only the unknown
  attributes that XML can hold, and leaves out the elements of an
  undeclared prefix. `:namespaces` wins over the URIs of the library.
  `inner-xml` escapes text for HTML. The rule parser and the schemas agree
  on the ranges of RFC 5545, and srcset reads URLs with commas in them.
- The companion formats, search and the player take odd input. A script is
  text up to its end tag, and `html/text` leaves it out; a pre keeps its
  indentation, and plain text stays plain. The cue timings of a transcript
  count before a tag, and a blank line in a body survives SRT and WebVTT.
  OPML reads xmlUrl in any case. Search folds the marks that text holds
  already, handles any limit fast, and reads an index read back from EDN.
  `player/skip` ignores a duration of zero or NaN, `player/write` stamps
  the entries of an app, and `changes-since` takes the state as it was, so
  that nothing merged since is missed.
- The same results with less work. A big feed parses in about half the time
  and emits in less than half. The repairs look at the characters in one
  loop before they use a pattern, and only an `xmlns:` attribute declares
  a prefix. `enqueue` finds the end of the queue without a sort, `turns`
  and `seasons` take linear time, `from-text` skips notes that hold no
  time, and search folds text many times faster.
- The inspector shows double quotes, which Replicant 2026.06.2 and later
  write as apostrophes. It parses a feed once, asks the server whether the
  feed changed, and keeps a bounded number of feeds. A bad parameter or a
  failing service gives a page, not an exception. The player merges what
  other tabs and sync steps did. The sync folder is dev-resources/sync,
  which `clean` keeps.
- In ClojureScript, the WebDAV store sends with js/fetch again when no
  `:fetch` is given. A chapter title, link or place name that is not text
  is left out and reported. After `sync!`, merge its state and then
  compact, or the merge brings back the tombstones that the step removed.
- `fetch!` reads the pages of a paged feed with `:max-pages`, and sends the
  `:credentials` of a private feed, or those in its URL. It gives
  `:permanent-url` when permanent redirects moved the feed, and
  `:not-before` when the server asks a client to wait, which `next-check`
  takes. `html/feed-links` finds the feeds that a web page names. On the
  JVM, a redirect to another host no longer gets the credentials of a
  request. A later page of a feed gets them only at the origin of the URL
  that the app gave.
- `resolve` reads the podroll, the publisher feed, the remote items of
  list and publisher feeds, where the comments on an episode are, the
  soundbites, and the texts of `podcast:txt` by purpose, with
  `ai-content?`. `publisher` also reads a remote item with the medium
  publisher in the channel, as some music feeds write it. When the
  settings of a show skip its outro, `player/step` ends an episode where
  the outro starts, with the new effect `[:stop id]`, and `outro-start`
  gives that position.
- The player state holds an inbox, stars, bookmarks, folders, tags and
  saved filters, and `search` selects by stars and by the inbox. `refresh`
  no longer brings back an episode that the listener played or took out
  on another device.
- OPML carries the folders, the tags as the categories of an outline, and
  the time the listener subscribed as the time an outline was created.
  `format.opml` reads and writes these, and the dates and owner of a list.
  `from-opml` takes the feed title before the text of an outline, and a
  folder around the whole list, as some apps write, is no folder.
- A listener can follow an OPML list that someone keeps on the web.
  `refresh-list` subscribes to the shows that join it, and OPML carries
  the followed lists as inclusions.
- Playlists beside the queue, which `to-list-feed` and `from-list-feed`
  connect to list feeds of the podcast namespace. `step` counts the
  seconds heard of each show by month, one count for each device, and
  `listened` adds them up.
- `format.media` reads the duration, the chapters and the artwork of MP3
  and M4A files: ID3v2 tags with chapters, MPEG audio frames, and MP4
  boxes with QuickTime or Nero chapters, also in a file written in
  fragments. `read!` fetches only the byte ranges that the reading needs,
  and `verify!` checks a file against its `podcast:integrity`, which
  `resolve/enclosures` now keeps. On the JVM, `http/call!` asks for byte
  ranges, and `http/digest!` streams a body through a digest.
- `check/feed!` checks over the network the files that a feed links to:
  HEAD and byte ranges, types and lengths, the headers of images, and
  CORS for transcripts and chapters, at each redirect on the way. The
  rules follow RFC 9110, RSS 2.0, the podcast namespace and the Fetch
  standard, and the profiles `:psp-1` and `:apple` add the rules for
  artwork. It moves two bytes of most files unless it is asked to read
  them, and sends the credentials of a private feed to the feed alone.
  `check/report` judges a check by another profile with no request sent
  again. A check looks at 500 files at most, and starts no file after ten
  minutes. The report counts the files that it left out.
  `check/default-limits` holds these limits. The inspector has a page for
  it.
- The readers of media files and images take hostile files in linear time
  and bounded stack: ID3 chapter frames nested deep, tables of contents
  that list each other, and MP4 sample tables of any size. Random and
  mangled files give a report, not an exception. A file can name sizes
  that it does not have. One reading fetches 32 MB of a file at most, and
  starts no request after a minute. `bytes/default-limits` holds the
  limits, and the options of a reading set each of them. A part that does
  not read, such as the chapters or the artwork, is left out, and the
  report names it. The rest of the file is read.
- `http/probe!` learns the facts of a file from a HEAD request and two
  bytes of it. `http/call!` also runs in ClojureScript, with fetch and
  limits of time and size, and on the JVM it gives the redirects it
  followed, with their headers. It reads no more of an answer than the
  range asked for. `format.image` reads the header of an image, and
  `bytes/read!` and `http/fetcher` now serve every reader that fetches by
  range: ranges near each other in one request, at most 16 requests a
  round, and further ahead in a walk through the segments of a file. A
  server that ignores ranges is asked once in a round. In Node,
  `http/call!` names podcast-clj as its User-Agent.
  `async/explain` adds the causes of a failure, where the JDK and Node
  tell why a connection failed.
- `app.sync.gpodder` syncs through the gpodder API, as AntennaPod, Kasts
  and gPodder do, with gpodder.net, the GPodder Sync app for Nextcloud and
  other servers: the subscriptions, the positions and the played marks.
  `identities` names shows and episodes over the app's feeds, and the
  actions for a feed that the app does not know yet wait for it. An action
  that names a time after the pull changes nothing, and the report names
  its device. The server counts an action as made when it arrived, at the
  latest.
  `app.sync.gpodder.server` serves the API over a player state, and the
  inspector serves it at `/api/2/` over its sync folder.
- `comments` reads the comments on an episode from the thread that
  `podcast:socialInteract` names. `comments.activitypub` reads threads over
  ActivityPub, and `comments.atproto` reads AT Protocol threads from an
  AppView that the app names. Both give comments and authors of one shape. It follows a conversation or the replies of
  each post, and takes each post only from its own server. When a server
  refuses unsigned requests or a browser blocks its answers, `fetch!` reads
  the thread through the Mastodon API of the server. `async/iteration`
  reads the pages of a collection. A post that does not read is a comment
  with an `:error`, and the posts around it are read. The inspector shows
  the comments on an episode.
- `value/parse-boostagram` reads the bLIP-10 record of a payment that a
  node received, and forgives what real apps write. A payment is one map
  for each way to pay, with its amounts in millisatoshis.
  `value.lnaddress` asks a Lightning address for an invoice with
  LNURL-pay, and checks its amount, or for a node with the keysend lookup.
  `value.metadata` writes and reads the RSS Payment Metadata of a payment
  to a Lightning address. `value/as-received` puts what a sender claims
  next to what the node received.
- `http/call-public!` sends nothing to the network of the app: no request
  to a host whose address is loopback, private, link-local, shared,
  multicast or reserved. On the JVM, it resolves the name and checks every
  redirect. Its guard is `http/public?`, which `http/call!` takes as
  `:allow?`. Each function that fetches what a feed or a stranger names
  takes `:allow?`, and puts it on each request. It is `http/public?` by
  default in `comments/fetch!`, `value.lnaddress/invoice!` and `node!`,
  and `value.metadata/fetch!`, since strangers choose their URLs.
- `analytics/downloads` counts the downloads of episodes from the requests
  that a host logs, by the rules of the IAB Tech Lab, version 2.2. It
  leaves out HEAD requests, requests for two bytes, bots and Apple Podcasts
  on a watch. It counts a listener once for each file in a day, or in 24
  hours from the first request. The requests must hold a minute of the
  file. `user-agents` reads the open list of user agents of the OPAWG, and
  `identify` names the app and the device of a request by it.
- `analytics.op3` puts the prefix of OP3 on the enclosures of a feed, and
  reads the downloads and counts of a show from the API of OP3. A download
  of OP3 has the keys of a download of `analytics/downloads`.
- podcast-clj compares the origins of URLs as the URL standard does, on
  both platforms. `http/absolute-url` resolves a URL as a browser does: it
  takes the spaces around a URL, and a path does not go above the root. On
  the JVM, `http/call!` fetches hosts with letters outside ASCII.
  `json/read-str` refuses JSON nested deeper than the stack reaches. On the
  JVM, it also refuses a number of more than a thousand digits.
- What podcast-clj knows about one service is a default that an app
  replaces with an option: the URLs of each directory, the root of the
  Podcast Index API, the Podping service for hosts, the instance of OP3
  and the URLs that it takes, the paths and the limits of the gpodder API,
  and the paths of statuses for the Mastodon API. A source of `directory`,
  the `:api` of a gpodder backend and a `:profile` are a name or an entry
  as data, so the paths of the GPodder Sync app for Nextcloud are now data
  in `gpodder/apis`. `doc/services.md` lists the defaults. `op3/prefix`
  puts one prefix on a URL for an instance of OP3 whose origin has a port.
