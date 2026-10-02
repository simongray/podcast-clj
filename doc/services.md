# Services and their defaults

Some services are known by name: the podcast directories, the Podcast
Index, Podping, OP3, the servers of the gpodder API and some relays of
Nostr. So are the OPML of some apps, the forms of the links of the apps,
and the rules of some apps for downloads. This page lists what
podcast-clj knows about each, and the option that replaces it.
[Principles](principles.md) gives the reason,
and [How to build a podcast app](building-an-app.md#use-another-service)
shows the calls.

Each namespace below is relative to `dk.simongray.podcast`.


## The rule

- A fact that a specification defines is a constant in the code. Examples
  are the well-known paths of LNURL-pay, the methods of the Hive API, and
  the calls of the Mastodon API.
- A fact about one service is a default. A public var holds it as data, and
  an option of the same name replaces it for one call.
- Where an option takes the name of an entry of a table, it also takes an
  entry as data. The entry has the shape of the entries of the table.


## The defaults, as of September 2026

| What | Var | Option | Default |
|---|---|---|---|
| The directories | `directory/sources` | a source, as a name or as an entry | Apple Podcasts, fyyd, gpodder.net and the Podcast Index |
| The root of the Podcast Index API | `directory.podcastindex/base-url` | `:base-url` | `https://api.podcastindex.org/api/1.0/` |
| The Hive nodes that Podping reads | `podping/nodes` | `:nodes`, or one `:node` | four public nodes |
| The service that writes a podping for a host | `podping/gateway` | `:gateway` | `https://podping.cloud/` |
| The request that tells a WebSub hub of a change | `websub/publish-form` | `:form` | the fields `hub.mode=publish` and `hub.url` |
| The genres of Apple Podcasts, for the chart of a category | `directory/apple-genres` | the `:genres` of a source | 110 genres |
| The forms of the links of the apps | `links/apps` | `:apps` | 48 apps and pages |
| The relays of Nostr | `comments.nostr/relays` | `:relays` | three public relays |
| The instance of OP3 | `analytics.op3/origin` | `:origin` | `https://op3.dev` |
| The URLs that OP3 takes behind its prefix | `analytics.op3/accepts?` | `:accepts?`, a predicate of a URL | the rules of op3.dev |
| The paths of the gpodder API | `app.sync.gpodder/apis` | `:api`, as a name or as a map of paths | `:gpodder` |
| The limits of a gpodder server | `app.sync.gpodder/default-limits` | `:page-size`, `:max-pages`, `:batch-size`, `:max-pending` | 1000, 10, 100 and 500 |
| The paths of statuses, for the Mastodon API | `comments.activitypub/status-paths` | `:status-paths` | the paths of four server programs |
| The requirements of a directory for a feed | `feed.validate/profiles` | `:profile`, as a name or as a map | no profile |
| The rules of a directory for artwork and media files | `check/profiles` | `:profile`, as a name or as a map | no profile |
| The folder that an app writes around a whole OPML list | `app.player/wrappers` | `:wrappers`, a set of names or a predicate of a name | `feeds` |

These services have no default, and the app gives them:

- the WebSub hub of a feed, which the feed names
- the AppView that reads AT Protocol threads, as `:appview`. As of
  September 2026, `https://public.api.bsky.app` of Bluesky and
  `https://api.blacksky.community` of Blacksky answer without an account.
- the root of a gpodder server, and the URL of a WebDAV collection.
  [Sync stores and documents](stores.md) lists some of them.
- the list of user agents of the OPAWG, which `analytics/user-agents` reads
  from the files that the app keeps


## The directories

An entry of `directory/sources` tells the API that the directory speaks,
and the URLs of the service:

| Name | `:api` | URLs |
|---|---|---|
| `:apple` | `:apple` | `:search` and `:lookup`, the URLs of the two calls. `:trending`, the root of the charts. |
| `:fyyd` | `:fyyd` | `:root` |
| `:gpodder` | `:gpodder` | `:root` |
| `:podcastindex` | `:podcastindex` | `:root`, and `:pages`, the URL of the pages of the shows |

The functions `request`, `response` and `call!` take a source as a name
or as an entry, and the `:sources` of `search!` and `fetch-chart!` hold
names and entries. The `:id` of an entry names the source in
`:directory/source`, in `:directory/sources` and in `:failed`. An entry
without an `:id` has the name of its API.


### The chart of a category

Each directory has its own way to name a category. These facts are from
the answers of the services on 29 September 2026:

| Directory | The chart of a category | The category |
|---|---|---|
| Apple Podcasts | The feed `{country}/rss/toppodcasts/limit={n}/genre={id}/json`, below the `:genre-charts` of the entry. It has the form of the older feeds of Apple, and it names no feed. A chart of one show has an object in the place of a list. | The id of a genre. `directory/apple-genres` has the ids by name, from the [genre service of Apple](https://itunes.apple.com/WebObjects/MZStoreServices.woa/ws/genres?id=26). |
| Podcast Index | The call `podcasts/trending` with the parameter `cat` | A name or an id of [categories.json](https://github.com/Podcastindex-org/podcast-namespace/blob/main/categories.json) of the podcast namespace, or more than one, with commas. The index gives the chart of all categories for a name that it does not know, and it tells no error. |
| gpodder.net | The call `api/2/tag/{tag}/{n}.json` | A tag, which is a word that the users of gpodder.net gave to a show |
| fyyd | None. The call `feature/podcast/hot` ignores a category. | |

The Podcast Index has the words of the names of Apple as its categories.
Society & Culture of Apple is Society and Culture there. So the names of
the top categories of Apple work for both, and `index/category-filter`
reads a name with an ampersand as its parts. A category of the second
level of Apple, such as Tech News, has no name in the Podcast Index. For
such a category, give a map with a category for each source.


## The gpodder API

The paths of the calls are in `app.sync.gpodder/apis`. In a path,
`{user}` is the user name, and `{device}` is the id of the device.

| Call | `:gpodder` | `:nextcloud` |
|---|---|---|
| `:login` | `/api/2/auth/{user}/login.json` | none |
| `:register` | `/api/2/devices/{user}/{device}.json` | none |
| `:subscriptions`, `:update-subscriptions` | `/api/2/subscriptions/{user}/{device}.json` | `/index.php/apps/gpoddersync/subscriptions`, `/index.php/apps/gpoddersync/subscription_change/create` |
| `:episodes`, `:update-episodes` | `/api/2/episodes/{user}.json` | `/index.php/apps/gpoddersync/episode_action`, `/index.php/apps/gpoddersync/episode_action/create` |

A backend logs in only when its API has a path for `:login`. It registers
its device only when the API has a path for `:register`.

The limits:

| Limit | Default | Meaning |
|---|---|---|
| `:page-size` | 1000 | A page of episode actions with this many actions is full. A pull then asks for one more page. gpodder.net gives 1000 actions at most. |
| `:max-pages` | 10 | The pages of episode actions that one pull reads |
| `:batch-size` | 100 | The episode actions in one request of a push. gpodder.net takes a longer list in the background, and reports no error of it. |
| `:max-pending` | 500 | The actions that the cursor keeps for feeds that the app does not know |


## The statuses of the Mastodon API

When the server of the root post refuses the reader, `comments/fetch!`
reads the thread through the Mastodon API. The URL of the root post must tell
the id of the status. Each entry of `comments.activitypub/status-paths` is
a regular expression of the whole path of a URL. Its one group is the id.

| Path | Server program |
|---|---|
| `/@USER/ID` | Mastodon |
| `/@USER/statuses/ID` | GoToSocial |
| `/users/USER/statuses/ID` | The ActivityPub id of a status of Mastodon and of GoToSocial |
| `/notice/ID` | Pleroma and Akkoma |


## OP3

The function `analytics.op3/accepts?` tells whether op3.dev takes a URL
behind its prefix. OP3 answers a URL that it does not take with an error, so the
episode does not play. It takes an http or https URL with a path and
without a user name. It does not take these domains:

- an IPv6 address
- a name without a dot
- a name that RFC 2606 keeps for tests: `.test`, `.example`, `.invalid`
  and `.localhost`

Another instance of OP3 can take other URLs. Give its `:origin`, and its
rules as `:accepts?`.


## The links of the apps

The forms of the links to a show and to an episode in each app are in
`links/apps`. The forms are those of the list [Podcast Platform
Links](https://github.com/nathangathright/podcast-platform-links) of
Nathan Gathright, as the list was on 29 September 2026. The table has
the forms that hold a key that the app did not make. It leaves out the
forms that need an id of the app itself, such as the id of a show at
Spotify.

A key in braces is a key of `links/ids`. A word before the key is an
encoding:

- `encoded` is the percent-encoding of a query.
- `base64url` is the base64 of URLs.
- `hex` is hexadecimal.
- `bare` is the URL without its scheme.

The app does not read `{slug}`, and `links/default-slug` is there. An
entry of the type page is a web page that leads a listener to the apps.

| Name | Type | Show | Episode | Subscribe |
|---|---|---|---|---|
| 1tuner | app | `https://1tuner.com/podcast/{slug}/{base64url feed-url}` | `https://1tuner.com/podcast/{slug}/{base64url feed-url}/{encoded episode-guid}` |  |
| AntennaPod | app | `https://antennapod.org/deeplink/subscribe?url={feed-url}` |  | `antennapod-subscribe://{bare feed-url}` |
| Anytime Player | app | `https://anytimeplayer.app/subscribe?url={feed-url}` |  |  |
| Apple Podcasts | app | `https://podcasts.apple.com/podcast/id{apple-id}` |  | `podcast://{bare feed-url}` |
| Breez | app | `https://breez.link/p?feedURL={encoded feed-url}` | `https://breez.link/p?feedURL={encoded feed-url}&episodeID={encoded episode-guid}` |  |
| Castamatic | app | `https://castamatic.com/guid/{podcast-guid}` |  | `castamatic://{bare feed-url}` |
| Castbox | app | `https://castbox.fm/vic/{apple-id}` |  |  |
| Castro | app | `https://castro.fm/itunes/{apple-id}` |  | `castros://subscribe/{bare feed-url}` |
| CurioCaster | app | `https://curiocaster.com/podcast/pi{podcastindex-id}` |  |  |
| Downcast | app |  |  | `downcast://{bare feed-url}` |
| Fountain | app | `https://fountain.fm/show/{podcastindex-id}` | `https://fountain.fm/episode/{podcastindex-episode-id}` |  |
| Goodpods | app | `https://www.goodpods.com/podcasts-aid/{apple-id}` |  |  |
| gPodder | app | `https://gpodder.net/subscribe?url={feed-url}` |  | `gpodder://{bare feed-url}` |
| Great Pods | page | `https://www.greatpods.co/podcasts?podcastIndexId={podcastindex-id}`<br>`https://www.greatpods.co/podcasts?itunesId={apple-id}` |  |  |
| iCatcher! | app |  |  | `icatcher://{bare feed-url}` |
| Metacast | app | `https://metacast.app/itunes/{apple-id}`<br>`https://open.metacast.app/podcasts/{podcast-guid}` |  |  |
| Moon FM | app | `https://moon.fm/itunes/{apple-id}` |  |  |
| Odesli | page | `https://pods.link/i/{apple-id}` |  |  |
| Overcast | app | `https://overcast.fm/itunes{apple-id}` |  | `overcast://x-callback-url/add?url={feed-url}` |
| Player FM | app | `https://player.fm/subscribe?id={encoded feed-url}` |  | `playerfm://{bare feed-url}` |
| Plink | page | `https://plinkhq.com/i/{apple-id}?to=page` |  |  |
| Pocket Casts | app | `https://pca.st/itunes/{apple-id}` |  | `pktc://subscribe/{bare feed-url}` |
| Pod Engine | page | `https://www.podengine.ai/podcasts/id/{apple-id}` |  |  |
| pod.link | page | `https://pod.link/{apple-id}`<br>`https://pod.link/{base64url feed-url}` |  |  |
| Podbean | app | `https://www.podbean.com/itunes/{apple-id}` |  |  |
| Podcast Addict | app | `https://podcastaddict.com/feed/{encoded feed-url}` | `https://podcastaddict.com/episode/{encoded enclosure-url}` | `podcastaddict://{bare feed-url}` |
| Podcast Details | page | `https://podcastdetails.com/?url={feed-url}` |  |  |
| Podcast Guru | app | `https://app.podcastguru.io/podcast/{apple-id}`<br>`https://app.podcastguru.io/podcast/X{hex feed-url}` |  | `podcastguru://{bare feed-url}` |
| Podcast Index | page | `https://podcastindex.org/podcast/{podcastindex-id}`<br>`https://podcastindex.org/podcast/{podcast-guid}` |  |  |
| Podcast Parrot | app | `https://podcastparrot.app/show?url={encoded feed-url}` |  |  |
| Podcast Republic | app | `https://www.podcastrepublic.net/podcast/{apple-id}` |  |  |
| Podcast X-Ray | page | `https://podcastxray.com/podcast/{apple-id}` |  |  |
| Podcat | app |  |  | `podcat://{bare feed-url}` |
| Podchaser | page | `https://podchaser.com/f/pod/{apple-id}` |  |  |
| podfollow | page | `https://podfollow.com/{apple-id}` |  |  |
| Podfriend | app |  | `https://podfriend.com/podcast/{apple-id}/{encoded episode-guid}` |  |
| Podkicker | app |  |  | `podkicker://subscribe/{bare feed-url}` |
| PodLP | app | `https://link.podlp.app/{apple-id}`<br>`https://link.podlp.app/{podcast-guid}` |  |  |
| Podnews | page | `https://podnews.net/podcast/{apple-id}`<br>`https://podnews.net/podcast/pi{podcastindex-id}`<br>`https://podnews.net/podcast/{podcast-guid}` |  |  |
| podStation | app | `https://podstation.github.io/subscribe-ext/?feedURL={feed-url}` |  |  |
| Podurama | app | `https://podurama.com/podcast/{slug}-i{apple-id}` |  |  |
| Podverse | app | `https://api.podverse.fm/api/v1/podcast/podcastindex/{podcastindex-id}` |  |  |
| RSSRadio | app |  |  | `rssradio://{bare feed-url}` |
| Sonnet | app | `https://sonnet.fm/p/{apple-id}` |  |  |
| Steno.fm | app | `https://steno.fm/show/{podcast-guid}` | `https://steno.fm/show/{podcast-guid}/episode/{base64url episode-guid}` |  |
| The Podcast App | app | `https://thepodcastapp.dev/p/itunes{apple-id}`<br>`https://thepodcastapp.dev/p/{podcastindex-id}` |  |  |
| TrueFans | app | `https://truefans.fm/{podcast-guid}` |  |  |
| YouTube Music | app | `https://music.youtube.com/library/podcasts?addrssfeed={base64url feed-url}` |  |  |

No app has a form for a link with a time. The apps that share an
episode at a time put the time on a link with their own id of the
episode, and no page of an app defines the form. A form is data, so an
app adds its forms to the table, with a key such as `:time` in its ids.
For the URL of a media file, `links/at-time` writes the media fragment
of the W3C, which a browser plays from the time.


## The relays of Nostr

The default relays for the comments of Nostr are in
`comments.nostr/relays`, and `comments/fetch!` asks them when the options
name no `:relays`. A root that names relays gets its questions to those
relays first.

The table tells what each relay answered on 29 September 2026. The
question was for the notes about one episode, by its id of NIP-73, which
the app Fountain wrote on that day.

| Relay | In `comments.nostr/relays` | The answer |
|---|---|---|
| `wss://relay.fountain.fm` | yes | Three notes about the episode. It gives no answer to a filter that names only the kind 1111. It does not read a filter of the tags `I` and `E`. |
| `wss://relay.damus.io` | yes | No note about the episode, and notes about other episodes |
| `wss://relay.primal.net` | yes | One note about the episode |
| `wss://nos.lol` | no | No note about an episode |
| `wss://relay.nostr.band` | no | No connection in ten seconds |

On that day, the relays had notes of kind 1 about episodes, with the id
of the episode in an `i` tag, as Fountain writes them. They had no comment
of kind 1111 on an episode, which is the form of NIP-22. The reader asks
for both.

An app that shows comments chooses its relays. Give them as `:relays`:

```clojure
(comments/fetch! thread {:relays ["wss://relay.example" "wss://relay.fountain.fm"]})
```


## The wallets of the listeners

The wallets follow NIP-47 in `value.nwc` and the guide of WebLN in
`value.webln`, as they read on 29 September 2026. No test went to a
wallet with money, so the table tells what the specifications say, and
not what a wallet answered.

| Specification | Where | What podcast-clj has of it |
|---|---|---|
| NIP-47, Nostr Wallet Connect | `github.com/nostr-protocol/nips`, the file `47.md` | The connection URI, the info event, the requests and the responses, and the commands `pay_invoice`, `make_invoice`, `lookup_invoice`, `get_balance` and `get_info` |
| NWC-04, Keysend Payments | `github.com/nostr-wallet-connect/nwc`, the file `04.md` | The command `pay_keysend`, with the custom records in hexadecimal |
| NWC-06, Metadata Conventions | The same repository, the file `06.md` | Nothing of its own. `pay-invoice` takes `:metadata` as the app gives it. |
| NIP-44, Encrypted Payloads | `github.com/nostr-protocol/nips`, the file `44.md` | Version 2, with the payloads of 65,536 bytes and more |
| WebLN | `webln.guide` | `enable`, `getInfo`, `sendPayment` and `keysend` |

On that day, NIP-47 was a core of five commands, and the other commands
were extensions in the repository nostr-wallet-connect/nwc. An older
NIP-47 had `pay_keysend` in its own text.

The options replace what podcast-clj chose:

| Choice | Default | Option |
|---|---|---|
| The time that `ask!` waits, and after which a request expires | 60 seconds, `nwc/default-limits` | `:timeout-seconds` |
| The relays that a request can go to | The public internet, `nostr/public?` | `:allow?` |
| What sends a request to a relay | `nostr/call!` | `:send` |
| The most bytes of an encrypted text | 65,535, `nip44/default-limits` | `:max-bytes` |
| The WebLN provider | That of the page, `webln/provider` | `:provider` |


## The rules of the apps for downloads

No standard defines the rules by which an app downloads episodes and
deletes files. The namespace `app.downloads` has the kinds of rule that these
apps have. The table tells the rules as the help pages of the apps gave them
on 29 September 2026, and the rule of `app.downloads` for each.

| App | The app's rule | In `app.downloads` |
|---|---|---|
| [Apple Podcasts](https://support.apple.com/en-us/102243) | Automatic downloads of a number of episodes, or of the episodes of a time | `:limit` and `:age` of `:episodes` |
| Apple Podcasts | No automatic downloads for one show | `:download?` of the rule of the show |
| Apple Podcasts | A played episode goes 24 hours after the play | `:played` |
| [Pocket Casts](https://support.pocketcasts.com/knowledge-base/archiving-episodes/) | A played episode goes at the end of the play, or some time later | `:played` |
| Pocket Casts | An episode goes when the listener did not play or download it for some time | `:inactive` |
| Pocket Casts | An episode with a star stays, when the listener wants that | `:keep` |
| Pocket Casts | A show keeps its newest 1, 2, 5 or 10 episodes | `:limit` of `:episodes`, with `:trim?` |
| [AntennaPod](https://antennapod.org/documentation/automation/automatic-downloads) | Automatic downloads of the episodes of the inbox, for each show | `:download?`, and `:inbox?` of `:episodes` |
| AntennaPod | A filter of a show: a minimum length, and words to include or to exclude | `:duration`, `:text` and `:not` of `:episodes` |
| AntennaPod | 50 episodes at most, until the listener gives another number | `:max-episodes` |
| [AntennaPod](https://antennapod.org/documentation/automation/deletion) | A file goes at the end of the play | `:played` of 0 |
| AntennaPod | Old files go when a download needs the room. The files of the queue and of the favorites stay. | `:make-room?`, and `:keep` |

The defaults of podcast-clj are in `app.downloads/default-rule` and
`app.downloads/default-rules`. They download nothing and delete nothing,
until the listener gives a rule.


## The folder around an OPML list

Some apps write the whole subscription list inside one outline. That outline
is a wrapper, and not the listener's own folder. OPML has no mark that tells
the two apart, so `player/from-opml` reads the name. A folder is a wrapper
when it holds every feed of the list and `app.player/wrappers` has its name.
The names are in lower case, and the case of the name in the list does not
count.

| Name | App that writes it | Seen in September 2026 |
|---|---|---|
| `feeds` | Pocket Casts | [an export file](https://github.com/razorgoto/podcast/blob/master/podcasts.opml) |
| `feeds` | Overcast | [an export file](https://github.com/vinhnx/podcasts/blob/main/overcast.opml) |

A folder with another name stays a folder. So the listener keeps the one
folder that holds all the shows, and the export of `player/to-opml` comes
back as it went out. For an app that writes another name, give the names
as `:wrappers`, or `(constantly true)` to take each folder that holds
every feed for a wrapper.


## The profiles

A profile of `feed.validate/profiles` holds the elements that a directory
requires and recommends, for the channel and for each item. A profile of
`check/profiles` holds the rules for the artwork, and the levels of the
diagnostics that the directory changes:

| Key | Meaning |
|---|---|
| `:name` | The name of the directory or the standard |
| `:artwork` | `:sizes`, the least and the greatest width in pixels. `:types`, the media types. `:colors`, the color models. `:extension?`, `:alpha?` and `:last-modified?`. |
| `:levels` | The level of a diagnostic, by its id |

Both tables have the profiles `:psp-1` and `:apple`. The first follows
[PSP-1](https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification)
of the Podcast Standards Project, and the second [A Podcaster's Guide to
RSS](https://help.apple.com/itc/podcasts_connect/#/itcb54353390) of Apple,
both as of 30 September 2026.
