# A threat model of the server and the handlers, October 2026

This page walks the parts of podcast-clj that face the network as one
attack surface: the podcast server, the handlers that a server of your
own can mount, and the parsers that read what the network brings. It
says who can attack what, which defences are there, which gaps are left,
and what to do about them. It was written on 11 October 2026 by reading
the code at 911059f and probing it at a REPL; the probes are quoted
where they decided a point.

The earlier reviews covered the money code and the parsers under hostile
input in September. This one adds the server, which the "Without
Tailscale" section of [its README](../../server/README.md) puts on the
public internet, and the composition of the handlers.


## What is exposed

The podcast server mounts four things behind one password, or behind
nothing on a private network:

- `/proxy`, the proxy of wary-fetch, which fetches any public URL for a
  page of the server's origin.
- `/dav/`, the WebDAV server of like-minded over the sync folder.
- `/api/2/`, the gpodder API over the listener's profile.
- the client's files, from a folder.

Inside the server runs a device of the listener: it syncs through the
same folder every minute, fetches the feeds that are due, and saves what
it learnt. So the server is also a client of every host that the
listener subscribes to, of the sync documents that the other devices
write, and of whatever the gpodder apps send.

A server of your own can mount the same handlers, and the WebSub
callback handler of websub-clj, which the podcast server doesn't mount.
Every handler says in its docstring that it checks no credentials.

The parsers behind all of this read untrusted documents: feeds (XML, with
repairs and the lenient parser), show notes (HTML), chapters and
transcripts (JSON, SRT, WebVTT, HTML), OPML, media files (ID3, MP4, HLS
playlists, images), the sync documents (EDN), the answers of directories
and OP3 (JSON), Podping blocks, Nostr events, and the gpodder bodies
(JSON).


## What is at stake

- The listener's profile: what they subscribe to, play and star, which
  the sync folder holds in the clear, and which anyone with the password
  can read and change.
- The server's availability: its memory, its disk and its sync loop.
- The server as a relay: the proxy fetches for whoever holds the
  password, and the device fetches whatever feeds the profile names, so
  a stranger with the password can make it a crawler.
- The other devices: a sync document that the server accepts reaches
  every device at its next sync.
- Secrets: the password, the credentials of private feeds, and the wallet
  connection, which lives in the client only and never in a store.


## Who can attack, and through what

### Anyone on the internet, without the password

Without a password the server is meant for a private network, and two
guards keep it there: it refuses any request whose Host header isn't
localhost or one of its names, which stops a web page from pointing a
name of its own at the server's address (DNS rebinding,
[service.clj:102](../../server/podcast_server/service.clj#L102)), and it
refuses whatever Tailscale Funnel brings from the public internet
([service.clj:125](../../server/podcast_server/service.clj#L125)). A request
without a Host header passes the first guard, which only matters for a
client that isn't a browser on the same network.

With a password, every request outside `/api/2/` must carry it in Basic
credentials, compared through their SHA-256 in constant time
([service.clj:136](../../server/podcast_server/service.clj#L136)). The
gpodder API checks the same password itself, for a session cookie or
Basic credentials, and refuses a user name in the path that isn't the
one that logged in
([server.cljc:321](../../src/dk/simongray/podcast/sync/gpodder/server.cljc#L321)).
Nothing slows down guesses, which the README says and the needs report
lists; the password's 16-character floor is the defence.

The headers of every answer come from Pedestal's secure headers: a
content security policy that allows scripts from the server alone, frame
options, nosniff, and HSTS. The request log holds the method and the
path, never the query string, so the URLs that the proxy fetches, tokens
included, don't land in it.

### Someone with the password

The password is the whole trust: whoever has it is the listener. The
README says that a friend who uses the server is the same listener, and
that's the design. What such a person can do beyond listening is the
first gap:

- **Fill the disk.** A put to `/dav/` takes 32 MB, and nothing limits
  how many documents the folder holds or what they add up to. The
  folder store takes any plain file name.
- **Exhaust memory through the proxy.** The proxy holds each body in
  memory, 32 MB at most, and Jetty answers up to fifty requests at once
  or more, so fifty large fetches at once ask for more than the 512 MB
  heap of the compose file.
- **Make the server a crawler.** A gpodder client can subscribe the
  profile to thousands of URLs, and the device fetches each of them,
  eight at a time, on the public internet only.
- **Reach every device.** A sync document that the server stores reaches
  the listener's other devices at their next sync. The documents are
  checked before they merge (below), so the damage is to the state, not
  to the devices.

None of this is an exploit; it's what the password grants. What's
missing is a ceiling on each: a quota on the sync folder, a cap on the
proxy's requests in flight, and a cap on the subscriptions a gpodder
push may add, so that a leaked password costs an afternoon rather than
the server.

### A web page in the listener's browser

A page of another site can't use the proxy: the proxy refuses a request
whose Sec-Fetch-Site is cross-site or whose Sec-Fetch-Mode is navigate,
and its answers carry a policy that runs no script and a resource policy
of same-origin
([proxy.cljc:97](https://github.com/simongray/wary-fetch/blob/master/src/dk/simongray/wary_fetch/proxy.cljc#L97)).
The WebDAV and gpodder handlers check neither header. Whether a page of
another site can write through them depends on the browser: on whether
it sends the Basic credentials it cached for the server with a cross-site
request, and on the gpodder session cookie, which is HttpOnly but names
no SameSite and no Secure attribute
([server.cljc:58](../../src/dk/simongray/podcast/sync/gpodder/server.cljc#L58)),
so the browser's default of Lax applies. The same-origin check that the
proxy has belongs on every write.

Show notes are the other way in. The views give them as Hiccup through
html-pieces, which keeps only the tags and attributes of its allowed
lists and drops scripts, styles, frames and forms
([episode.cljc:100](../../src/dk/simongray/podcast/app/episode.cljc#L100),
[html_pieces.cljc:51](https://github.com/simongray/html-pieces/blob/master/src/dk/simongray/html_pieces.cljc#L51)),
and the comments go through the same sanitising. A client that renders
the raw description of the feed instead has to sanitise it itself.

### A malicious host, or any URL a validator is given

The device, the proxy and every function that fetches read documents
from servers that the listener chose, or that a stranger submitted to a
validator. The defences are in wary-fetch and in the parsers:

- **Where a request may go.** A request goes only where its `:allow-pred`
  allows, and the JVM and Node clients ask it about every redirect
  ([jvm.clj:159](https://github.com/simongray/wary-fetch/blob/master/src/dk/simongray/wary_fetch/jvm.clj#L159)).
  The predicate `url/public?` resolves the name and requires every
  address to be public, and the JDK caches the answer for 30 seconds, so
  the request goes to the addresses that were checked. The podcast
  server gives it to the proxy and to the device. The library's own
  default allows everything, which is right for an app on a phone and
  wrong for a server, and the guide says so for the checker. The
  inspector allows everything
  ([service.clj:518](../../web/inspector/service.clj#L518)); it's a
  development tool, and its docs should say that it mustn't face the
  internet.
- **Redirects.** A redirect from https to http isn't followed, and the
  credentials of the request stay behind when a redirect leaves the
  origin ([redirect.cljc:33](https://github.com/simongray/wary-fetch/blob/master/src/dk/simongray/wary_fetch/redirect.cljc#L33)),
  so a private feed's password doesn't follow a 302 to another site.
  Five redirects at most.
- **Size and time.** Every body is read within `:max-bytes`, 100 MB by
  default and 32 MB in the proxy, counted after gzip is undone, so a
  gzip bomb inflates no further than the limit; every exchange ends at
  its deadline, with a watchdog that closes a slow body
  ([jvm.clj:76](https://github.com/simongray/wary-fetch/blob/master/src/dk/simongray/wary_fetch/jvm.clj#L76)).
- **XML.** The parser runs StAX without DTDs and without external
  entities, so an external entity and an entity bomb stay as the literal
  text `&xxe;` and `&lol2;`, which the ampersand repair then reports;
  nesting is checked, so a feed of 100,000 nested elements is refused
  as malformed in 66 ms. The repairs are linear: two million bare
  ampersands take half a second, three million stray brackets a tenth.
- **JSON and EDN.** A document of 200,000 nested brackets overflows the
  JVM's stack; the JSON adapter catches it and gives nil, the chapters
  and transcript readers give an empty document, and like-minded
  catches it as a malformed sync document. The server's sync loop
  catches anything a step throws and logs it, so one bad document
  doesn't stop the loop
  ([device.clj:108](../../server/podcast_server/device.clj#L108)).
- **Media files.** The readers ask for byte ranges and read within a
  budget of 32 MB, so a box or a frame that claims a huge size can't make
  them read it.

What a malicious host can still do is cost time: a feed of 30 MB parses
in half a second, and a thousand of them a few hours of CPU, within the
limits. A per-domain cap on the device's fetches would bound it.

### A hub, a chain, a relay, a directory

- **WebSub.** The callback handler gives the hub's challenge back only
  when the app's `:wanted-pred` says so, takes a challenge of 512
  characters of the allowed alphabet at most, checks the signature in
  constant time, and answers 200 to a delivery with a wrong signature
  without giving the body to the app, as the spec's 2026 update asks. A
  delivery above `:max-bytes` gets 413. A hub can still flood a callback
  with deliveries; the handler's docstring tells the app to return fast
  from `:deliver!`.
- **Podping.** The pings are read from a Hive API node and kept only
  when their writer is one that the podping account follows, read from
  the chain. A lying node can make the device fetch a feed early, which
  is a refresh.
- **Nostr.** Every event is verified against its id and signature before
  it counts, and an exchange with a relay reads 500 events and 5 MB at
  most. The wallet protocol was reviewed as money code in September.
- **Directories and OP3.** Their JSON answers are read within the size
  limit and field by field, so an unexpected shape gives a diagnostic
  rather than an exception.

### A peer device

A sync document from another device, or from the friend, is decoded
with the EDN reader and nothing else, so it can't run code; it's refused
unless it's an envelope with a version and a state, and unless the
profile's own check finds its state fit to merge
([document.cljc:100](https://github.com/simongray/like-minded/blob/master/src/dk/simongray/like_minded/document.cljc#L100)).
A document of a newer version is left out until the device updates. What
a peer can still do is grow the profile: entries never leave a CRDT until
they're tombstoned and seen by every device, so a device that writes a
million stars costs every device memory and compaction time. The needs
report lists bounded growth as a nice-to-have; for a server with a
friend it's the one way a peer hurts the others.

A peer can also set its clock ahead, and its writes then win every tie
until the others catch up; [Syncing](../sync.md) says so, and seconds of
skew are harmless.


## Gaps, ranked

| Gap | Who | What to do | Size |
|---|---|---|---|
| Writes through WebDAV and the gpodder API have no same-origin check, and the session cookie has no SameSite or Secure | a web page in the listener's browser | Refuse a write whose Sec-Fetch-Site is cross-site, as the proxy does, and set `SameSite=Strict; Secure` on the cookie when the request came over https | an hour |
| No quota on the sync folder | the friend, a leaked password | A cap on the count and the bytes of the documents of a store, in the WebDAV handler or the folder store | a day |
| No cap on the proxy's requests in flight | the friend, a leaked password | A semaphore of a few requests in the proxy handler, with 503 and Retry-After beyond it | an hour |
| No cap on what a gpodder push subscribes | a gpodder client with the password | A limit on new subscriptions per push, and a report | an hour |
| An uncaught exception logs the request with its Authorization header; a WebDAV put with a 300-character name is one | anyone with the password, and whoever reads the logs | Catch the I/O exception in the WebDAV handler, and hide the Authorization header after the login so no log holds it | half a day |
| Without a password, the only guard refuses Tailscale Funnel; behind Caddy or any other proxy the server is open | anyone | Refuse to start without a password unless bound to localhost, and require it in the compose file | an hour |
| A gpodder push accepts any string as a subscription, and the device fetches it every minute | a gpodder client with the password | Refuse what isn't a public web URL | an hour |
| Nothing slows down password guesses | anyone | Known; a delay that grows per address, careful of a shared NAT | an hour |
| The inspector allows every address and has no login | anyone, if it's exposed | Say in its docs that it's for localhost, and give it `url/public?` when it binds elsewhere | an hour |
| Private feed URLs with tokens reach the server's log through the refresh report | the operator's logs | Log the diagnostic without its URL, or with the token masked | an hour |
| A request without a Host header passes the names check | a non-browser client on the network | Refuse it too, or document that the check is for browsers | an hour |


## For a server of your own

If you mount the handlers yourself, the rules that the podcast server
follows are the ones to keep:

- Put a login in front of every handler, since none checks credentials.
- Give `url/public?` as `:allow-pred` to the proxy, to the device's
  config and to the checker, so nothing fetches your own network.
- Keep the limits: the proxy's 32 MB, the WebDAV handler's 32 MB per
  document, the gpodder handler's 10 MB per request, and the sizes of
  the sync documents.
- Give each user a store of their own, as the WebDAV handler's docstring
  shows, and a gpodder handler of their own.
- Refuse cross-site writes, and set the cookie attributes above.
- Terminate TLS in front of the server, since it speaks plain HTTP.
- Expect the password to leak one day, and bound what it can cost.
